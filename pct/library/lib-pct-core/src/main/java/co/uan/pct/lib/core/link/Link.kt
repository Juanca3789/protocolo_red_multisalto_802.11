package co.uan.pct.lib.core.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import co.uan.pct.lib.core.link.codec.ControlPacket
import co.uan.pct.lib.core.link.network.StaParentNetwork
import co.uan.pct.lib.core.link.table.NeighborEntry
import co.uan.pct.lib.core.link.table.NeighborTable
import co.uan.pct.lib.core.link.tcp.ControlTcpSession
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.radio.GoControlEndpoint
import co.uan.pct.lib.core.types.NodeId
import co.uan.pct.lib.core.types.Role
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Capa link: escucha `:8765` en la red del GO propio y abre el upstream TCP por la STA al padre.
 * L1 entrega [GoControlEndpoint] solo cuando la interfaz P2P ya tiene dirección (LK-01).
 */
@OptIn(ExperimentalUuidApi::class)
class Link(
    context: Context,
    private val localNodeId: NodeId,
    private val roleFlow: StateFlow<Role>,
    private val goEndpointFlow: StateFlow<GoControlEndpoint?>,
    private val staNetworkFlow: StateFlow<Network?>,
    private val parentBssidFlow: StateFlow<String?>,
) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val neighborTable = NeighborTable()

    val neighbors: StateFlow<List<NeighborEntry>> = neighborTable.entries

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var goEndpointWatchJob: Job? = null
    private var staNetworkWatchJob: Job? = null
    private var staUpstreamJob: Job? = null
    private val goListenSockets = mutableListOf<ServerSocket>()
    private val goAcceptJobs = mutableListOf<Job>()
    private var goListenBoundEndpoint: GoControlEndpoint? = null
    private var upstreamSession: ControlTcpSession? = null

    private val goSessions =
        Collections.synchronizedSet(mutableSetOf<ControlTcpSession>())

    private var active = false

    fun start() {
        if (active) return
        active = true
        PctLog.event("link: start")
        goEndpointWatchJob = scope.launch {
            combine(roleFlow, goEndpointFlow) { role, endpoint -> role to endpoint }
                .collect { (role, endpoint) ->
                    if (!active) return@collect
                    if (role == Role.ISLAND || endpoint == null) {
                        closeGoControlListen()
                        closeAllGoSessions()
                    } else if (endpoint != goListenBoundEndpoint) {
                        closeGoControlListen()
                        openGoControlListen(endpoint)
                    }
                }
        }
        staNetworkWatchJob = scope.launch {
            combine(roleFlow, staNetworkFlow, parentBssidFlow) { role, staNetwork, bssid ->
                Triple(role, staNetwork, bssid)
            }.collect { (role, staNetwork, bssid) ->
                staUpstreamJob?.cancel()
                staUpstreamJob = null
                upstreamSession?.closeQuietly()
                upstreamSession = null
                if (!active || role == Role.ISLAND || staNetwork == null) return@collect
                staUpstreamJob = launch { maintainUpstreamOverSta(staNetwork, bssid) }
            }
        }
    }

    fun stop() {
        if (!active) return
        PctLog.event("link: stop")
        active = false
        goEndpointWatchJob?.cancel()
        staNetworkWatchJob?.cancel()
        staUpstreamJob?.cancel()
        goEndpointWatchJob = null
        staNetworkWatchJob = null
        staUpstreamJob = null
        upstreamSession?.closeQuietly()
        upstreamSession = null
        closeAllGoSessions()
        closeGoControlListen()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        neighborTable.clear()
    }

    /**
     * Un ServerSocket por dirección de la interfaz GO (IPv4 `.1` y `fe80::` con ámbito).
     * No hay API pública para atar un ServerSocket a un [Network]; la IPv4 y la fe80 del
     * GO existen solo en su interfaz, así que el bind por dirección equivale a escuchar ahí.
     * LK-02: el endpoint se marca como atado únicamente si el bind IPv4 tuvo éxito.
     */
    private fun openGoControlListen(endpoint: GoControlEndpoint) {
        val ipv4Ok = bindGoListen(InetAddress.getByName(endpoint.hostIpv4), endpoint.iface)
        if (!ipv4Ok) return
        goListenBoundEndpoint = endpoint
        endpoint.linkLocalIpv6?.let { fe80 -> bindGoListen(fe80, endpoint.iface) }
    }

    private fun bindGoListen(address: InetAddress, iface: String): Boolean =
        runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, CONTROL_PORT))
            }
        }.onSuccess { server ->
            goListenSockets.add(server)
            PctLog.event("link: listen go :$CONTROL_PORT bind=${address.hostAddress} iface=$iface")
            goAcceptJobs.add(scope.launch { acceptGoControlConnections(server) })
        }.onFailure { t ->
            PctLog.event("link: listen go fallo bind=${address.hostAddress} ${t.message}")
        }.isSuccess

    private fun closeGoControlListen() {
        goAcceptJobs.forEach { it.cancel() }
        goAcceptJobs.clear()
        goListenSockets.forEach { runCatching { it.close() } }
        goListenSockets.clear()
        goListenBoundEndpoint = null
    }

    private suspend fun acceptGoControlConnections(server: ServerSocket) {
        while (active && scope.isActive && roleFlow.value != Role.ISLAND) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            PctLog.event("link: go accept peer=${client.inetAddress?.hostAddress}")
            openGoInboundSession(client)
        }
    }

    private fun openGoInboundSession(socket: Socket) {
        var sessionRef: ControlTcpSession? = null
        val session = ControlTcpSession(
            socket = socket,
            scope = scope,
            localNodeId = localNodeId,
            neighborTable = neighborTable,
            mode = ControlTcpSession.Mode.GO_INBOUND,
            goHostIpv4 = { goEndpointFlow.value?.hostIpv4 },
            onChildAnnounced = { s -> onChildAnnounced(s) },
            onReachBetter = { ingress, dest, hops -> floodReach(except = ingress, dest = dest, hopsFromHere = hops) },
            onClosed = { sessionRef?.let { goSessions.remove(it) } },
        )
        sessionRef = session
        goSessions.add(session)
        session.start()
    }

    private fun closeAllGoSessions() {
        goSessions.toList().forEach { it.closeQuietly() }
        goSessions.clear()
    }

    /**
     * Destino upstream: `fe80::` del padre (EUI-64 del BSSID) con ámbito de la interfaz STA.
     * La IPv4 `192.168.49.1` es fallback: en un BRIDGE también es nuestra propia IP de GO y el
     * kernel la enruta a `lo` (PC-08, LK-05).
     */
    private suspend fun maintainUpstreamOverSta(staNetwork: Network, parentBssid: String?) {
        var fallbackLogged = false
        while (
            active &&
            scope.isActive &&
            roleFlow.value != Role.ISLAND &&
            staNetworkFlow.value == staNetwork
        ) {
            val fe80 = StaParentNetwork.fe80GatewayForControlTcp(connectivity, staNetwork, parentBssid)
            val target: InetAddress? = fe80 ?: StaParentNetwork.gatewayForControlTcp(connectivity, staNetwork)
            val localIp = StaParentNetwork.hostIpv4ForAnnounce(connectivity, staNetwork)
            if (target == null || localIp == null) {
                delay(RETRY_DELAY)
                continue
            }
            if (fe80 == null && !fallbackLogged) {
                fallbackLogged = true
                PctLog.event("link: upstream ipv4 fallback gateway=${target.hostAddress} bssid=$parentBssid")
            }
            val connected = runCatching {
                Socket().apply {
                    staNetwork.bindSocket(this)
                    connect(InetSocketAddress(target, CONTROL_PORT), CONNECT_TIMEOUT_MS)
                }
            }.getOrNull()
            if (connected == null) {
                PctLog.event("link: upstream connect fallo dst=${target.hostAddress}")
                delay(RETRY_DELAY)
                continue
            }
            PctLog.event("link: upstream ${if (fe80 != null) "fe80" else "ipv4"} dst=${target.hostAddress}")
            val session = ControlTcpSession(
                socket = connected,
                scope = scope,
                localNodeId = localNodeId,
                neighborTable = neighborTable,
                mode = ControlTcpSession.Mode.UPSTREAM,
                goHostIpv4 = { goEndpointFlow.value?.hostIpv4 },
                onReachBetter = { ingress, dest, hops -> floodReach(except = ingress, dest = dest, hopsFromHere = hops) },
            )
            upstreamSession = session
            session.start()
            runCatching {
                session.runUpstreamJoin(localIp)
            }.onFailure { t ->
                PctLog.event("link: upstream session fallo ${t.message}")
                session.closeQuietly()
                upstreamSession = null
                delay(RETRY_DELAY)
                continue
            }
            while (
                active &&
                scope.isActive &&
                roleFlow.value != Role.ISLAND &&
                staNetworkFlow.value == staNetwork &&
                upstreamSession === session
            ) {
                delay(SESSION_HOLD)
            }
            session.closeQuietly()
            if (upstreamSession === session) {
                upstreamSession = null
            }
        }
    }

    private fun onChildAnnounced(childSession: ControlTcpSession) {
        val child = childSession.peerNodeId ?: return
        dumpTableTo(childSession, except = child.identifier)
        floodReach(except = childSession, dest = child, hopsFromHere = 1)
    }

    private fun dumpTableTo(session: ControlTcpSession, except: Uuid) {
        for (entry in neighborTable.entries.value) {
            if (entry.nodeId.identifier == except) continue
            session.send(ControlPacket.Reach(entry.nodeId, entry.hops).toWireBytes())
        }
    }

    private fun floodReach(except: ControlTcpSession?, dest: NodeId, hopsFromHere: Int) {
        val frame = ControlPacket.Reach(dest, hopsFromHere).toWireBytes()
        for (session in controlSessions()) {
            if (session === except || !session.isIdentified) continue
            if (session.peerUuid == dest.identifier) continue
            session.send(frame)
        }
    }

    private fun controlSessions(): List<ControlTcpSession> {
        val out = mutableListOf<ControlTcpSession>()
        upstreamSession?.let { out.add(it) }
        out.addAll(goSessions)
        return out
    }

    private companion object {
        private const val CONTROL_PORT = 8765
        private const val CONNECT_TIMEOUT_MS = 15_000
        private val RETRY_DELAY = 2.seconds
        private val SESSION_HOLD = 1.seconds
    }
}
