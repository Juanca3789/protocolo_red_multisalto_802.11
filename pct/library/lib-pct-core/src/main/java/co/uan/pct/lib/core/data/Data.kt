package co.uan.pct.lib.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import co.uan.pct.lib.core.data.codec.DataPacket
import co.uan.pct.lib.core.data.tcp.DataTcpSession
import co.uan.pct.lib.core.link.network.StaParentNetwork
import co.uan.pct.lib.core.link.table.NeighborEntry
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.radio.GoControlEndpoint
import co.uan.pct.lib.core.types.NodeId
import co.uan.pct.lib.core.types.Role
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Capa data: broadcast greedy por árbol en TCP :8766.
 * Recibe → dedup → ACK al ingreso → entrega local → repite al padre y a todos los hijos menos
 * por donde entró. Nadie mira para quién es; la responsabilidad es por salto.
 * Misma geometría que [co.uan.pct.lib.core.link.Link]: escucha en el endpoint GO que entrega L1,
 * upstream por la STA a la fe80 del padre. Solo habla con vecinos que ya están en la tabla L2.
 */
@OptIn(ExperimentalUuidApi::class)
class Data(
    context: Context,
    private val localNodeId: NodeId,
    private val roleFlow: StateFlow<Role>,
    private val goEndpointFlow: StateFlow<GoControlEndpoint?>,
    private val staNetworkFlow: StateFlow<Network?>,
    private val parentBssidFlow: StateFlow<String?>,
    private val neighborsFlow: StateFlow<List<NeighborEntry>>,
) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private var incoming = Channel<Pair<NodeId, ByteArray>>(capacity = Channel.UNLIMITED)
    private var sendQueue = Channel<Outgoing>(capacity = Channel.UNLIMITED)
    private val seen = SeenCache()
    private val nextMsgId = AtomicLong(1)

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var goEndpointWatchJob: Job? = null
    private var upstreamWatchJob: Job? = null
    private var upstreamJob: Job? = null
    private var sendWorkerJob: Job? = null
    private val goListenSockets = mutableListOf<ServerSocket>()
    private val goAcceptJobs = mutableListOf<Job>()
    private var goListenBoundEndpoint: GoControlEndpoint? = null
    private var upstreamSession: DataTcpSession? = null

    private val sessions =
        Collections.synchronizedSet(mutableSetOf<DataTcpSession>())

    private var active = false

    fun start() {
        if (active) return
        active = true
        PctLog.event("data: start")
        goEndpointWatchJob = scope.launch {
            combine(roleFlow, goEndpointFlow) { role, endpoint -> role to endpoint }
                .collect { (role, endpoint) ->
                    if (!active) return@collect
                    if (role == Role.ISLAND || endpoint == null) {
                        closeGoListen()
                        closeGoSessions()
                    } else if (endpoint != goListenBoundEndpoint) {
                        closeGoListen()
                        closeGoSessions()
                        openGoListen(endpoint)
                    }
                }
        }
        upstreamWatchJob = scope.launch {
            val hasNeighbors = neighborsFlow.map { it.isNotEmpty() }.distinctUntilChanged()
            combine(roleFlow, staNetworkFlow, parentBssidFlow, hasNeighbors) { role, sta, bssid, ready ->
                UpstreamGate(role, sta, bssid, ready)
            }.collect { gate ->
                upstreamJob?.cancel()
                upstreamJob = null
                upstreamSession?.closeQuietly()
                upstreamSession = null
                if (!active || gate.role == Role.ISLAND || gate.staNetwork == null || !gate.hasNeighbors) {
                    return@collect
                }
                upstreamJob = launch { maintainUpstreamOverSta(gate.staNetwork, gate.parentBssid) }
            }
        }
        sendWorkerJob = scope.launch { sendWorker() }
    }

    fun stop() {
        if (!active) return
        PctLog.event("data: stop")
        active = false
        goEndpointWatchJob?.cancel()
        upstreamWatchJob?.cancel()
        upstreamJob?.cancel()
        sendWorkerJob?.cancel()
        goEndpointWatchJob = null
        upstreamWatchJob = null
        upstreamJob = null
        sendWorkerJob = null
        upstreamSession?.closeQuietly()
        upstreamSession = null
        closeGoSessions()
        closeGoListen()
        incoming.close()
        sendQueue.close()
        incoming = Channel(capacity = Channel.UNLIMITED)
        sendQueue = Channel(capacity = Channel.UNLIMITED)
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        seen.clear()
    }

    /** Broadcast greedy: solo vecinos hop 1. */
    suspend fun broadcast(payload: ByteArray) {
        require(payload.size <= DataPacket.MAX_PAYLOAD_BYTES) { "payload too large" }
        sendQueue.send(Outgoing.Bcast(payload))
    }

    /** Unicast: next-jump = IP de la fila; se manda a esa sesión hop 1 y se desliga. */
    suspend fun send(destination: NodeId, payload: ByteArray) {
        require(payload.size <= DataPacket.MAX_PAYLOAD_BYTES) { "payload too large" }
        sendQueue.send(Outgoing.Uni(destination, payload))
    }

    /** `(origen, payload)`; el nombre del origen sale de la tabla L2 si es vecino, si no va vacío. */
    suspend fun pop(): Pair<NodeId, ByteArray> = incoming.receive()

    // ---- envío local ----

    private suspend fun sendWorker() {
        while (scope.isActive) {
            when (val out = runCatching { sendQueue.receive() }.getOrNull() ?: break) {
                is Outgoing.Bcast -> emitLocalBcast(out.payload)
                is Outgoing.Uni -> emitLocalUnicast(out.dest, out.payload)
            }
        }
    }

    private fun emitLocalBcast(payload: ByteArray) {
        val bcast = DataPacket.Bcast(
            msgId = nextMsgId.getAndIncrement(),
            originUuid = localNodeId.identifier,
            tsMs = System.currentTimeMillis(),
            payload = payload,
        )
        seen.markIfNew(bcast.originUuid, bcast.msgId)
        deliver(localNodeId, bcast.payload)
        val fanout = fanoutHop1(bcast.toWireBytes(), except = null)
        PctLog.event("data: bcast local msg=${bcast.msgId} bytes=${payload.size} fanout=$fanout")
    }

    private fun emitLocalUnicast(dest: NodeId, payload: ByteArray) {
        if (dest.identifier == localNodeId.identifier) {
            incoming.trySend(localNodeId to payload)
            return
        }
        val uni = DataPacket.Unicast(
            msgId = nextMsgId.getAndIncrement(),
            originUuid = localNodeId.identifier,
            destUuid = dest.identifier,
            tsMs = System.currentTimeMillis(),
            payload = payload,
        )
        seen.markIfNew(uni.originUuid, uni.msgId)
        if (!forwardUnicast(uni, except = null)) {
            PctLog.event("data: unicast sin ruta dest=${dest.name} id=${dest.identifier}")
        }
    }

    // ---- relay ----

    private fun onBcast(ingress: DataTcpSession, bcast: DataPacket.Bcast) {
        if (!seen.markIfNew(bcast.originUuid, bcast.msgId)) {
            PctLog.emit(LOG_TAG, Log.DEBUG, "dedup drop msg=${bcast.msgId} origin=${bcast.originUuid}")
            return
        }
        ingress.enqueue(DataPacket.BcastRecv(bcast.msgId, bcast.originUuid).toWireBytes())
        deliver(NodeId(bcast.originUuid, nameFor(bcast.originUuid)), bcast.payload)
        val fanout = fanoutHop1(bcast.toWireBytes(), except = ingress)
        PctLog.event(
            "data: bcast relay msg=${bcast.msgId} origin=${bcast.originUuid} " +
                "from=${ingress.peerUuid} fanout=$fanout",
        )
    }

    private fun onUnicast(ingress: DataTcpSession, uni: DataPacket.Unicast) {
        if (!seen.markIfNew(uni.originUuid, uni.msgId)) {
            PctLog.emit(LOG_TAG, Log.DEBUG, "dedup drop uni msg=${uni.msgId} origin=${uni.originUuid}")
            return
        }
        ingress.enqueue(DataPacket.UnicastRecv(uni.msgId, uni.originUuid).toWireBytes())
        if (uni.destUuid == localNodeId.identifier) {
            deliver(NodeId(uni.originUuid, nameFor(uni.originUuid)), uni.payload)
            PctLog.event("data: unicast deliver msg=${uni.msgId} from=${uni.originUuid}")
            return
        }
        if (!forwardUnicast(uni, except = ingress)) {
            PctLog.event("data: unicast sin next-jump dest=${uni.destUuid}")
        }
    }

    private fun deliver(origin: NodeId, payload: ByteArray) {
        incoming.trySend(origin to payload)
    }

    /** Broadcast: recorre la tabla y solo escribe a hop == 1. */
    private fun fanoutHop1(frame: ByteArray, except: DataTcpSession?): Int {
        var count = 0
        for (entry in neighborsFlow.value) {
            if (entry.hops != 1) continue
            val session = sessionForPeer(entry.nodeId.identifier) ?: continue
            if (session === except) continue
            session.enqueue(frame)
            count++
        }
        return count
    }

    private fun forwardUnicast(uni: DataPacket.Unicast, except: DataTcpSession?): Boolean {
        val row = neighborsFlow.value.firstOrNull { it.nodeId.identifier == uni.destUuid } ?: return false
        val hop1 = neighborsFlow.value.firstOrNull { it.hops == 1 && it.ipv4OnLink == row.ipv4OnLink }
            ?: return false
        val session = sessionForPeer(hop1.nodeId.identifier) ?: return false
        if (session === except) return false
        session.enqueue(uni.toWireBytes())
        PctLog.event(
            "data: unicast next=${hop1.nodeId.name} dest=${uni.destUuid} hops=${row.hops}",
        )
        return true
    }

    private fun sessionForPeer(uuid: Uuid): DataTcpSession? =
        sessions.toList().firstOrNull { it.isIdentified && it.peerUuid == uuid }

    private fun nameFor(uuid: Uuid): String {
        if (uuid == localNodeId.identifier) return localNodeId.name
        return neighborsFlow.value.firstOrNull { it.nodeId.identifier == uuid }?.nodeId?.name
            ?: uuid.toString().take(8)
    }

    private fun isNeighbor(uuid: Uuid): Boolean =
        neighborsFlow.value.any { it.nodeId.identifier == uuid && it.hops == 1 }

    // ---- GO: escucha :8766 ----

    private fun openGoListen(endpoint: GoControlEndpoint) {
        val ipv4Ok = bindGoListen(InetAddress.getByName(endpoint.hostIpv4), endpoint.iface)
        if (!ipv4Ok) return
        goListenBoundEndpoint = endpoint
        endpoint.linkLocalIpv6?.let { fe80 -> bindGoListen(fe80, endpoint.iface) }
    }

    private fun bindGoListen(address: InetAddress, iface: String): Boolean =
        runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, DATA_PORT))
            }
        }.onSuccess { server ->
            goListenSockets.add(server)
            PctLog.event("data: listen go :$DATA_PORT bind=${address.hostAddress} iface=$iface")
            goAcceptJobs.add(scope.launch { acceptLoop(server) })
        }.onFailure { t ->
            PctLog.event("data: listen go fallo bind=${address.hostAddress} ${t.message}")
        }.isSuccess

    private fun closeGoListen() {
        goAcceptJobs.forEach { it.cancel() }
        goAcceptJobs.clear()
        goListenSockets.forEach { runCatching { it.close() } }
        goListenSockets.clear()
        goListenBoundEndpoint = null
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (active && scope.isActive && roleFlow.value != Role.ISLAND) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            PctLog.event("data: go accept peer=${client.inetAddress?.hostAddress}")
            val session = DataTcpSession(
                socket = client,
                scope = scope,
                localNodeId = localNodeId,
                mode = DataTcpSession.Mode.GO_INBOUND,
                acceptPeer = ::isNeighbor,
                onBcast = ::onBcast,
                onUnicast = ::onUnicast,
                onClosed = { sessions.remove(it) },
            )
            sessions.add(session)
            session.start()
        }
    }

    private fun closeGoSessions() {
        sessions.toList()
            .filter { it !== upstreamSession }
            .forEach { it.closeQuietly() }
    }

    // ---- hijo: upstream :8766 por la STA ----

    private suspend fun maintainUpstreamOverSta(staNetwork: Network, parentBssid: String?) {
        while (
            active &&
            scope.isActive &&
            roleFlow.value != Role.ISLAND &&
            staNetworkFlow.value == staNetwork
        ) {
            val fe80 = StaParentNetwork.fe80GatewayForControlTcp(connectivity, staNetwork, parentBssid)
            val target: InetAddress? = fe80 ?: StaParentNetwork.gatewayForControlTcp(connectivity, staNetwork)
            if (target == null) {
                delay(RETRY_DELAY)
                continue
            }
            val connected = runCatching {
                Socket().apply {
                    staNetwork.bindSocket(this)
                    connect(InetSocketAddress(target, DATA_PORT), CONNECT_TIMEOUT_MS)
                }
            }.getOrNull()
            if (connected == null) {
                PctLog.event("data: upstream connect fallo dst=${target.hostAddress}")
                delay(RETRY_DELAY)
                continue
            }
            PctLog.event("data: upstream ${if (fe80 != null) "fe80" else "ipv4"} dst=${target.hostAddress}")
            val session = DataTcpSession(
                socket = connected,
                scope = scope,
                localNodeId = localNodeId,
                mode = DataTcpSession.Mode.UPSTREAM,
                acceptPeer = ::isNeighbor,
                onBcast = ::onBcast,
                onUnicast = ::onUnicast,
                onClosed = { sessions.remove(it) },
            )
            upstreamSession = session
            sessions.add(session)
            session.start()
            val hello = runCatching { session.runUpstreamHello() }
            if (hello.isFailure) {
                PctLog.event("data: upstream hello fallo ${hello.exceptionOrNull()?.message}")
                session.closeQuietly()
                upstreamSession = null
                delay(RETRY_DELAY)
                continue
            }
            PctLog.event("data: upstream listo dst=${connected.remoteSocketAddress}")
            while (
                active &&
                scope.isActive &&
                roleFlow.value != Role.ISLAND &&
                staNetworkFlow.value == staNetwork &&
                upstreamSession === session &&
                session.isIdentified
            ) {
                delay(SESSION_HOLD)
            }
            session.closeQuietly()
            if (upstreamSession === session) {
                upstreamSession = null
            }
        }
    }

    private sealed class Outgoing {
        class Bcast(val payload: ByteArray) : Outgoing()
        class Uni(val dest: NodeId, val payload: ByteArray) : Outgoing()
    }

    private data class UpstreamGate(
        val role: Role,
        val staNetwork: Network?,
        val parentBssid: String?,
        val hasNeighbors: Boolean,
    )

    private companion object {
        const val LOG_TAG = "Data"
        const val DATA_PORT = 8766
        const val CONNECT_TIMEOUT_MS = 15_000
        val RETRY_DELAY = 2.seconds
        val SESSION_HOLD = 1.seconds
    }
}
