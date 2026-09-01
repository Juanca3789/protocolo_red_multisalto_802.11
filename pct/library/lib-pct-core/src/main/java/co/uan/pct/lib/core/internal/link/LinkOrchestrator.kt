package co.uan.pct.lib.core.internal.link

import android.net.ConnectivityManager
import android.net.Network
import co.uan.pct.lib.core.api.DataChannelState
import co.uan.pct.lib.core.api.NeighborIface
import co.uan.pct.lib.core.api.PctConfig
import co.uan.pct.lib.core.internal.route.RouteOrchestrator
import co.uan.pct.lib.core.internal.tcp.DataChannelAckPayload
import co.uan.pct.lib.core.internal.tcp.DataChannelOpenPayload
import co.uan.pct.lib.core.internal.tcp.HelloPayload
import co.uan.pct.lib.core.internal.tcp.JoinCommitPayload
import co.uan.pct.lib.core.internal.tcp.PctControlServerSocket
import co.uan.pct.lib.core.internal.tcp.PctControlSocket
import co.uan.pct.lib.core.internal.tcp.PctDataServerSocket
import co.uan.pct.lib.core.internal.tcp.PctDataSocket
import co.uan.pct.lib.core.internal.tcp.PctFrameCodec
import co.uan.pct.lib.core.internal.tcp.PctMsgType
import co.uan.pct.lib.core.internal.tcp.PctUuidCodec
import co.uan.pct.lib.core.internal.tcp.PingPayload
import co.uan.pct.lib.core.internal.util.PctNetworkHelper
import co.uan.pct.lib.core.internal.util.PctNid
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LinkOrchestrator(
    private val parentScope: CoroutineScope,
    private val config: PctConfig,
    private val registry: NeighborRegistry,
    private val routes: RouteOrchestrator,
    private val connectivityManager: ConnectivityManager,
    private val selfNid: () -> String,
    private val selfRole: () -> String,
    private val selfHop: () -> Int,
    private val parentNid: () -> String?,
    private val log: (String) -> Unit,
    private val onTopologyChanged: () -> Unit,
) {
    private val linkJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val linkScope = CoroutineScope(linkJob + Dispatchers.Main.immediate)

    private var ctrlServer: PctControlServerSocket? = null
    private var dataServer: PctDataServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pingSeq = AtomicInteger(0)

    private val ctrlReadJobs = ConcurrentHashMap<String, Job>()
    private val dataReadJobs = ConcurrentHashMap<String, Job>()

    private var upstreamNetwork: Network? = null
    private var listenNetwork: Network? = null

    private data class UpstreamParams(
        val parentNid: String,
        val parentRole: String,
        val parentHop: Int,
    )

    private var upstreamParams: UpstreamParams? = null
    private var upstreamReconnectJob: Job? = null

    fun onGoReady(listenNetwork: Network? = null) {
        this.listenNetwork = listenNetwork
        if (!running.compareAndSet(false, true)) return
        log("[L2] GO ready — accept :${config.ctrlPort}/:${config.dataPort}")
        linkScope.launch(Dispatchers.IO) { controlAcceptLoop() }
        linkScope.launch(Dispatchers.IO) { dataAcceptLoop() }
        linkScope.launch(Dispatchers.IO) { controlKeepaliveLoop() }
    }

    fun onStaConnected(
        parentNidValue: String,
        parentRole: String,
        parentHop: Int,
        network: Network?,
    ) {
        upstreamParams = UpstreamParams(parentNidValue, parentRole, parentHop)
        upstreamNetwork = network
        linkScope.launch(Dispatchers.IO) {
            connectUpstream(parentNidValue, parentRole, parentHop, network)
        }
    }

    fun close() {
        running.set(false)
        upstreamReconnectJob?.cancel()
        upstreamReconnectJob = null
        upstreamParams = null
        linkJob.cancelChildren()
        ctrlReadJobs.values.forEach { it.cancel() }
        dataReadJobs.values.forEach { it.cancel() }
        ctrlReadJobs.clear()
        dataReadJobs.clear()
        runCatching { ctrlServer?.close() }
        runCatching { dataServer?.close() }
        ctrlServer = null
        dataServer = null
        listenNetwork = null
        upstreamNetwork = null
        linkScope.launch(Dispatchers.IO) {
            registry.all().forEach { registry.remove(it.neighborNid) }
        }
    }

    suspend fun linkStats(): Triple<Int, Int, Int> = registry.linkStats()

    private suspend fun ensureCtrlServer(): PctControlServerSocket = withContext(Dispatchers.IO) {
        ctrlServer ?: PctControlServerSocket(config.ctrlPort, listenNetwork).also { ctrlServer = it }
    }

    private suspend fun ensureDataServer(): PctDataServerSocket = withContext(Dispatchers.IO) {
        dataServer ?: PctDataServerSocket(config.dataPort, listenNetwork).also { dataServer = it }
    }

    private suspend fun controlAcceptLoop() {
        while (linkScope.isActive && running.get()) {
            runCatching {
                val server = ensureCtrlServer()
                val socket = withContext(Dispatchers.IO) { server.acceptControl() }
                val remoteIp = socket.peerIp()
                if (remoteIp.isBlank()) {
                    socket.close()
                    return@runCatching
                }
                log("[L2] control accept from $remoteIp")
                linkScope.launch(Dispatchers.IO) {
                    runCatching {
                        handleInboundControl(socket, remoteIp)
                    }.onFailure { e ->
                        log("[L2] control accept ($remoteIp): ${e.message}")
                        runCatching { socket.close() }
                    }
                }
            }.onFailure { e ->
                if (running.get()) {
                    log("[L2] controlAccept: ${e.message}")
                }
                delay(500)
            }
        }
    }

    private suspend fun dataAcceptLoop() {
        while (linkScope.isActive && running.get()) {
            runCatching {
                val server = ensureDataServer()
                val socket = withContext(Dispatchers.IO) { server.acceptData() }
                val remoteIp = socket.peerIp()
                if (remoteIp.isBlank()) {
                    socket.close()
                    return@runCatching
                }
                log("[L2] data accept from $remoteIp")
                attachDataSocket(remoteIp, socket)
            }.onFailure { e ->
                if (running.get()) {
                    log("[L2] dataAccept: ${e.message}")
                }
                delay(500)
            }
        }
    }

    private suspend fun handleInboundControl(socket: PctControlSocket, remoteIp: String) {
        val frame = withContext(Dispatchers.IO) { socket.readFrame() }
        if (frame.msgType != PctMsgType.HELLO) {
            log("[L2] expected HELLO, got 0x${frame.msgType.toString(16)}")
            socket.close()
            return
        }
        val hello = PctFrameCodec.decodeHello(frame.payload)
        if (hello.senderNid == selfNid()) {
            log("[L2] self-HELLO ignorado ip=$remoteIp")
            socket.close()
            return
        }
        val record = NeighborRecord(
            neighborNid = hello.senderNid,
            iface = NeighborIface.DOWNSTREAM,
            localIp = remoteIp,
            ctrlPort = config.ctrlPort,
            dataPort = config.dataPort,
            ctrlSocket = socket,
            role = PctFrameCodec.roleFromWire(hello.role),
            hop = hello.hop,
            lastCtrlMs = System.currentTimeMillis(),
        )
        registry.upsert(record)
        val reply = buildHello()
        withContext(Dispatchers.IO) { socket.writeRaw(PctFrameCodec.encodeHello(reply)) }
        log("[L2] HELLO downstream ${hello.senderNid} ip=$remoteIp")

        startControlReadLoop(hello.senderNid)
        routes.refreshFromRegistry()
        onTopologyChanged()
    }

    private suspend fun connectUpstream(
        parentNidValue: String,
        parentRole: String,
        parentHop: Int,
        network: Network?,
    ) {
        ctrlReadJobs[parentNidValue]?.cancel()
        ctrlReadJobs.remove(parentNidValue)
        registry.remove(parentNidValue)

        val net = network ?: upstreamNetwork
        if (net == null) {
            log("[L2] upstream: sin Network STA")
            return
        }
        upstreamNetwork = net
        val parentIp = PctNetworkHelper.gatewayIp(connectivityManager, net)
            ?: PctNetworkHelper.DEFAULT_P2P_GW
        controlConnectUpstream(parentNidValue, parentRole, parentHop, parentIp, net)
    }

    private fun scheduleUpstreamReconnect() {
        val p = upstreamParams ?: return
        if (!running.get()) return
        upstreamReconnectJob?.cancel()
        upstreamReconnectJob = linkScope.launch(Dispatchers.IO) {
            delay(2_000)
            if (!linkScope.isActive || !running.get()) return@launch
            log("[L2] upstream reconnect → ${p.parentNid.take(8)}…")
            connectUpstream(p.parentNid, p.parentRole, p.parentHop, upstreamNetwork)
        }
    }

    private suspend fun controlConnectUpstream(
        parentNidValue: String,
        parentRole: String,
        parentHop: Int,
        parentIp: String,
        network: Network,
    ) {
        repeat(5) { attempt ->
            if (attempt > 0) delay(2_000L)
            runCatching {
                val socket = withContext(Dispatchers.IO) {
                    PctControlSocket(parentIp, config.ctrlPort, network)
                }
                val hello = buildHello()
                withContext(Dispatchers.IO) { socket.writeRaw(PctFrameCodec.encodeHello(hello)) }

                val replyFrame = withContext(Dispatchers.IO) { socket.readFrame() }
                check(replyFrame.msgType == PctMsgType.HELLO) {
                    "expected HELLO, got 0x${replyFrame.msgType.toString(16)}"
                }
                val parentHello = PctFrameCodec.decodeHello(replyFrame.payload)
                if (parentHello.senderNid == selfNid()) {
                    throw IOException("upstream self-connect")
                }
                val canonicalParentNid = parentHello.senderNid
                if (canonicalParentNid != parentNidValue) {
                    registry.remove(parentNidValue)
                    upstreamParams = UpstreamParams(canonicalParentNid, parentRole, parentHop)
                    log("[L2] nid padre vía HELLO: ${canonicalParentNid.take(8)}…")
                }

                val record = NeighborRecord(
                    neighborNid = canonicalParentNid,
                    iface = NeighborIface.UPSTREAM,
                    localIp = parentIp,
                    ctrlPort = config.ctrlPort,
                    dataPort = config.dataPort,
                    ctrlSocket = socket,
                    role = parentRole.ifBlank { PctFrameCodec.roleFromWire(parentHello.role) },
                    hop = parentHop,
                    lastCtrlMs = System.currentTimeMillis(),
                )
                registry.upsert(record)
                log("[L2] HELLO upstream ${canonicalParentNid} ip=$parentIp")

                val join = JoinCommitPayload(committerNid = selfNid(), epoch = 1, via = 0)
                withContext(Dispatchers.IO) {
                    socket.writeRaw(PctFrameCodec.encodeJoinCommit(join))
                }

                startControlReadLoop(canonicalParentNid)
                routes.refreshFromRegistry()
                onTopologyChanged()
                return
            }.onFailure { e ->
                log("[L2] upstream intento ${attempt + 1}/5: ${e.message}")
            }
        }
    }

    private fun startControlReadLoop(neighborNid: String) {
        ctrlReadJobs[neighborNid]?.cancel()
        ctrlReadJobs[neighborNid] = linkScope.launch(Dispatchers.IO) {
            controlReadLoop(neighborNid)
        }
    }

    private suspend fun controlReadLoop(neighborNid: String) {
        val record = registry.get(neighborNid) ?: return
        val socket = record.ctrlSocket ?: return
        while (linkScope.isActive) {
            runCatching {
                val frame = socket.readFrame()
                when (frame.msgType) {
                    PctMsgType.JOIN_COMMIT -> {
                        val join = PctFrameCodec.decodeJoinCommit(frame.payload)
                        log("[L2] JOIN_COMMIT ${join.committerNid}")
                        openDataChannelForChild(neighborNid)
                    }
                    PctMsgType.PING -> {
                        val ping = PctFrameCodec.decodePing(frame.payload)
                        socket.writeRaw(PctFrameCodec.encodePong(PingPayload(selfNid(), ping.seq)))
                        registry.update(neighborNid) { it.lastCtrlMs = System.currentTimeMillis() }
                    }
                    PctMsgType.PONG -> {
                        registry.update(neighborNid) { it.lastCtrlMs = System.currentTimeMillis() }
                    }
                    PctMsgType.DATA_CHANNEL_OPEN -> {
                        val open = PctFrameCodec.decodeDataChannelOpen(frame.payload)
                        handleDataChannelOpen(neighborNid, open)
                    }
                    PctMsgType.DATA_CHANNEL_ACK -> {
                        registry.update(neighborNid) {
                            it.dataChannelState = DataChannelState.OPEN
                            it.lastDataMs = System.currentTimeMillis()
                        }
                        log("[L2] DATA_CHANNEL_ACK $neighborNid")
                        routes.refreshFromRegistry()
                        routes.broadcastTopo { nid, bytes -> writeCtrlRaw(nid, bytes) }
                        onTopologyChanged()
                    }
                    PctMsgType.DATA_CHANNEL_RESET -> {
                        val target = PctFrameCodec.decodeDataChannelReset(frame.payload)
                        log("[L2] DATA_CHANNEL_RESET $target")
                        requestDataReconnect(target)
                    }
                    PctMsgType.TOPO_UPDATE -> {
                        val topo = PctFrameCodec.decodeTopoUpdate(frame.payload)
                        routes.handleTopoUpdate(neighborNid, topo)
                    }
                    else -> log("[L2] ctrl ignore 0x${frame.msgType.toString(16)} from $neighborNid")
                }
            }.onFailure { e ->
                log("[L2] control read end $neighborNid: ${e.message}")
                val iface = record.iface
                registry.remove(neighborNid)
                ctrlReadJobs.remove(neighborNid)
                routes.refreshFromRegistry()
                onTopologyChanged()
                if (iface == NeighborIface.UPSTREAM) {
                    scheduleUpstreamReconnect()
                }
                return
            }
        }
    }

    private suspend fun openDataChannelForChild(childNid: String) {
        val open = DataChannelOpenPayload(childNid, config.dataPort, epoch = 1)
        writeCtrlRaw(childNid, PctFrameCodec.encodeDataChannelOpen(open))
        log("[L2] DATA_CHANNEL_OPEN → $childNid :${config.dataPort}")
    }

    private suspend fun handleDataChannelOpen(neighborNid: String, open: DataChannelOpenPayload) {
        val record = registry.get(neighborNid) ?: return
        val ip = record.localIp
        if (ip.isBlank()) return
        runCatching {
            registry.update(neighborNid) {
                it.dataChannelState = DataChannelState.RECONNECTING
            }
            val dataSocket = withContext(Dispatchers.IO) {
                PctDataSocket(ip, open.dataPort, upstreamNetwork)
            }
            registry.update(neighborNid) {
                it.dataSocket = dataSocket
                it.dataChannelState = DataChannelState.OPEN
                it.lastDataMs = System.currentTimeMillis()
            }
            val ack = DataChannelAckPayload(selfNid(), status = 0)
            writeCtrlRaw(neighborNid, PctFrameCodec.encodeDataChannelAck(ack))
            startDataReadLoop(neighborNid)
            log("[L2] data connect → $ip:${open.dataPort}")
            routes.refreshFromRegistry()
            routes.broadcastTopo { nid, bytes -> writeCtrlRaw(nid, bytes) }
            onTopologyChanged()
        }.onFailure { e ->
            log("[L2] data connect failed: ${e.message}")
            registry.update(neighborNid) { it.dataChannelState = DataChannelState.CLOSED }
        }
    }

    private suspend fun attachDataSocket(remoteIp: String, socket: PctDataSocket) {
        val ip = PctNetworkHelper.normalizeHost(remoteIp)
        var record = registry.getByIp(ip)
        if (record == null) {
            record = registry.downstream().firstOrNull {
                it.dataChannelState != DataChannelState.OPEN
            }
        }
        if (record == null) {
            log("[L2] data accept: sin vecino para $ip")
            socket.close()
            return
        }
        registry.update(record.neighborNid) {
            if (it.localIp.isBlank()) it.localIp = ip
            it.dataSocket = socket
            it.dataChannelState = DataChannelState.OPEN
            it.lastDataMs = System.currentTimeMillis()
        }
        startDataReadLoop(record.neighborNid)
        log("[L2] data OPEN ${record.neighborNid} ip=$ip")
        routes.refreshFromRegistry()
        onTopologyChanged()
    }

    private fun startDataReadLoop(neighborNid: String) {
        dataReadJobs[neighborNid]?.cancel()
        dataReadJobs[neighborNid] = linkScope.launch(Dispatchers.IO) {
            dataReadLoop(neighborNid)
        }
    }

    private suspend fun dataReadLoop(neighborNid: String) {
        val record = registry.get(neighborNid) ?: return
        val socket = record.dataSocket ?: return
        while (linkScope.isActive) {
            runCatching {
                val frame = socket.readFrame()
                if (frame.msgType == PctMsgType.DATA) {
                    val user = PctFrameCodec.decodeUserData(frame.payload)
                    registry.update(neighborNid) { it.lastDataMs = System.currentTimeMillis() }
                    routes.handleUserData(user)
                }
            }.onFailure { e ->
                log("[L2] data read end $neighborNid: ${e.message}")
                registry.update(neighborNid) {
                    it.dataChannelState = DataChannelState.RECONNECTING
                    it.dataSocket = null
                }
                dataReadJobs.remove(neighborNid)
                runCatching { socket.close() }
                return
            }
        }
    }

    private suspend fun controlKeepaliveLoop() {
        delay(5_000)
        while (linkScope.isActive && running.get()) {
            delay(config.pingIntervalMs)
            val seq = pingSeq.incrementAndGet()
            val ping = PingPayload(selfNid(), seq)
            val frame = PctFrameCodec.encodePing(ping)
            for (n in registry.all()) {
                if (n.ctrlSocket != null && !n.ctrlSocket!!.isClosed) {
                    runCatching { writeCtrlRaw(n.neighborNid, frame) }
                }
            }
        }
    }

    private suspend fun requestDataReconnect(neighborNid: String) {
        val record = registry.get(neighborNid) ?: return
        registry.update(neighborNid) {
            runCatching { it.dataSocket?.close() }
            it.dataSocket = null
            it.dataChannelState = DataChannelState.RECONNECTING
        }
        when (record.iface) {
            NeighborIface.DOWNSTREAM -> openDataChannelForChild(neighborNid)
            NeighborIface.UPSTREAM -> {
                val open = DataChannelOpenPayload(selfNid(), config.dataPort, epoch = 1)
                writeCtrlRaw(neighborNid, PctFrameCodec.encodeDataChannelOpen(open))
            }
        }
    }

    private suspend fun writeCtrlRaw(neighborNid: String, frame: ByteArray) {
        val record = registry.get(neighborNid) ?: return
        val socket = record.ctrlSocket ?: return
        withContext(Dispatchers.IO) {
            socket.writeRaw(frame)
        }
    }

    private fun buildHello(): HelloPayload {
        val parent = PctNid.forWire(parentNid())
            ?: PctUuidCodec.fromBytes(PctUuidCodec.zeroBytes())
        return HelloPayload(
            senderNid = selfNid(),
            role = PctFrameCodec.roleToWire(selfRole()),
            parentNid = parent,
            epoch = 1,
            treeVersion = 1,
            hop = selfHop(),
            capabilities = 3,
            neighborCount = 0,
        )
    }
}
