package co.uan.pct.lib.core.link.tcp

import co.uan.pct.lib.core.link.codec.ControlPacket
import co.uan.pct.lib.core.link.codec.readControlPacket
import co.uan.pct.lib.core.link.table.NeighborTable
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.types.NodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Un TCP :8765: cola de salida + lector; PING/PONG, ANNOUNCE/OK, REACH y UNREACH. */
@OptIn(ExperimentalUuidApi::class)
internal class ControlTcpSession(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val localNodeId: NodeId,
    private val neighborTable: NeighborTable,
    private val mode: Mode,
    private val goHostIpv4: () -> String?,
    private val onChildAnnounced: ((ControlTcpSession) -> Unit)? = null,
    private val onReachBetter: ((ingress: ControlTcpSession, dest: NodeId, localHops: Int) -> Unit)? = null,
    private val onUnreach: ((ingress: ControlTcpSession, dest: Uuid) -> Unit)? = null,
    private val onClosed: (() -> Unit)? = null,
) {
    enum class Mode {
        UPSTREAM,
        GO_INBOUND,
    }

    private val outbound = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val nextSeq = AtomicInteger(1)
    private val pendingPong = AtomicReference<CompletableDeferred<Int>?>(null)
    private val pendingAnnounceOk =
        AtomicReference<CompletableDeferred<ControlPacket.AnnounceOk>?>(null)

    private var writerJob: Job? = null
    private var readerJob: Job? = null
    private var keepAliveJob: Job? = null

    @Volatile
    var peerNodeId: NodeId? = null
        private set

    @Volatile
    var peerIpv4: String? = null
        private set

    val peerUuid: Uuid?
        get() = peerNodeId?.identifier

    val isIdentified: Boolean
        get() = peerNodeId != null && socketAlive()

    val remoteInetAddress: InetAddress?
        get() = runCatching { socket.inetAddress }.getOrNull()

    @Volatile
    private var closed = false

    fun start() {
        socket.soTimeout = READ_TIMEOUT_MS
        socket.tcpNoDelay = true
        writerJob = scope.launch { writerLoop() }
        readerJob = scope.launch { readerLoop() }
    }

    fun closeQuietly() {
        if (closed) return
        closed = true
        keepAliveJob?.cancel()
        readerJob?.cancel()
        writerJob?.cancel()
        outbound.close()
        runCatching { socket.close() }
        onClosed?.invoke()
    }

    fun send(frame: ByteArray) {
        if (!socketAlive()) return
        outbound.trySend(frame)
    }

    suspend fun runUpstreamJoin(staLocalIpv4: String) {
        pingAwaitPong()
        announceAwaitOk(staLocalIpv4)
        keepAliveJob = scope.launch {
            while (scope.isActive && socketAlive()) {
                delay(PING_INTERVAL)
                runCatching { pingAwaitPong() }
                    .onFailure { t ->
                        PctLog.event("link: upstream keepalive fallo ${t.message}")
                        closeQuietly()
                    }
            }
        }
    }

    private suspend fun pingAwaitPong() {
        require(mode == Mode.UPSTREAM)
        if (!socketAlive()) error("socket dead")
        val seq = nextSeq.getAndIncrement()
        val ack = CompletableDeferred<Int>()
        pendingPong.set(ack)
        send(ControlPacket.Ping(seq).toWireBytes())
        withTimeout(PONG_WAIT) {
            val got = ack.await()
            if (got != seq) error("pong seq mismatch")
        }
        pendingPong.set(null)
    }

    private suspend fun announceAwaitOk(staLocalIpv4: String) {
        require(mode == Mode.UPSTREAM)
        if (!socketAlive()) error("socket dead")
        val ack = CompletableDeferred<ControlPacket.AnnounceOk>()
        pendingAnnounceOk.set(ack)
        send(ControlPacket.Announce(localNodeId, staLocalIpv4).toWireBytes())
        PctLog.event("link: announce upstream ip=$staLocalIpv4")
        withTimeout(ANNOUNCE_OK_WAIT) {
            ack.await()
        }
        pendingAnnounceOk.set(null)
    }

    private suspend fun writerLoop() {
        val output: OutputStream = socket.getOutputStream()
        while (scope.isActive) {
            val frame = runCatching { outbound.receive() }.getOrNull() ?: break
            if (!socketAlive()) break
            runCatching {
                output.write(frame)
                output.flush()
            }.onFailure { t ->
                PctLog.event("link: control write fallo ${t.message}")
                break
            }
        }
    }

    private suspend fun readerLoop() {
        val input = socket.getInputStream()
        try {
            while (scope.isActive && socketAlive()) {
                when (val packet = runCatching { readControlPacket(input) }.getOrNull()) {
                    null -> break
                    is ControlPacket.Ping -> if (mode == Mode.GO_INBOUND) {
                        send(ControlPacket.Pong(packet.seq).toWireBytes())
                    }
                    is ControlPacket.Pong -> pendingPong.get()?.complete(packet.seq)
                    is ControlPacket.Announce -> if (mode == Mode.GO_INBOUND) {
                        handleAnnounceOnGo(packet)
                    }
                    is ControlPacket.AnnounceOk -> handleAnnounceOk(packet)
                    is ControlPacket.Reach -> handleReach(packet)
                    is ControlPacket.Unreach -> handleUnreach(packet)
                }
            }
        } finally {
            if (mode == Mode.GO_INBOUND) {
                closeQuietly()
            }
        }
    }

    private fun handleAnnounceOnGo(packet: ControlPacket.Announce) {
        if (packet.nodeId.identifier == localNodeId.identifier) return
        peerNodeId = packet.nodeId
        peerIpv4 = packet.ipv4OnLink
        neighborTable.addEntry(packet.nodeId, packet.ipv4OnLink, hops = 1)
        PctLog.event(
            "link: announce child=${packet.nodeId.name} " +
                "id=${packet.nodeId.identifier} ip=${packet.ipv4OnLink} hops=1",
        )
        val ourIp = goHostIpv4()?.trim().orEmpty()
        if (ourIp.isEmpty()) {
            PctLog.event("link: announce_ok omitido (sin goHostIpv4)")
            return
        }
        send(ControlPacket.AnnounceOk(localNodeId, ourIp).toWireBytes())
        PctLog.event("link: announce_ok child=${packet.nodeId.name} local=$ourIp")
        onChildAnnounced?.invoke(this)
    }

    private fun handleAnnounceOk(packet: ControlPacket.AnnounceOk) {
        if (mode != Mode.UPSTREAM) return
        if (packet.nodeId.identifier != localNodeId.identifier) {
            peerNodeId = packet.nodeId
            peerIpv4 = packet.ipv4OnLink
            neighborTable.addEntry(packet.nodeId, packet.ipv4OnLink, hops = 1)
            PctLog.event(
                "link: announce_ok parent=${packet.nodeId.name} " +
                    "id=${packet.nodeId.identifier} ip=${packet.ipv4OnLink} hops=1",
            )
        }
        pendingAnnounceOk.get()?.complete(packet)
    }

    private fun handleReach(packet: ControlPacket.Reach) {
        if (packet.nodeId.identifier == localNodeId.identifier) return
        val via = peerIpv4 ?: return
        val hops = packet.hopsFromReporter + 1
        if (!neighborTable.installIfBetter(packet.nodeId, via, hops)) return
        PctLog.event(
            "link: reach dest=${packet.nodeId.name} id=${packet.nodeId.identifier} " +
                "via=$via hops=$hops",
        )
        onReachBetter?.invoke(this, packet.nodeId, hops)
    }

    private fun handleUnreach(packet: ControlPacket.Unreach) {
        if (packet.destUuid == localNodeId.identifier) return
        val via = peerIpv4 ?: return
        if (!neighborTable.removeIfVia(packet.destUuid, via)) return
        PctLog.event("link: unreach dest=${packet.destUuid} via=$via")
        onUnreach?.invoke(this, packet.destUuid)
    }

    private fun socketAlive(): Boolean = !closed && !socket.isClosed && socket.isConnected

    private companion object {
        const val READ_TIMEOUT_MS = 15_000
        val PONG_WAIT = 8.seconds
        val ANNOUNCE_OK_WAIT = 8.seconds
        val PING_INTERVAL = 5.seconds
    }
}
