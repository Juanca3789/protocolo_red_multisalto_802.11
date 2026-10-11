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
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

/** Un TCP :8765: cola de salida + lector; PING/PONG y ANNOUNCE/ANNOUNCE_OK. */
@OptIn(ExperimentalUuidApi::class)
internal class ControlTcpSession(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val localNodeId: NodeId,
    private val neighborTable: NeighborTable,
    private val mode: Mode,
    private val goHostIpv4: () -> String?,
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
        enqueue(ControlPacket.Ping(seq).toWireBytes())
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
        enqueue(ControlPacket.Announce(localNodeId, staLocalIpv4).toWireBytes())
        PctLog.event("link: announce upstream ip=$staLocalIpv4")
        withTimeout(ANNOUNCE_OK_WAIT) {
            val parent = ack.await()
            if (parent.nodeId.identifier == localNodeId.identifier) return@withTimeout
            neighborTable.addEntry(parent.nodeId, parent.ipv4OnLink, hops = 1)
            PctLog.event(
                "link: announce_ok parent=${parent.nodeId.name} " +
                        "id=${parent.nodeId.identifier} ip=${parent.ipv4OnLink} hops=1",
            )
        }
        pendingAnnounceOk.set(null)
    }

    private suspend fun enqueue(frame: ByteArray) {
        if (!socketAlive()) error("socket dead")
        outbound.send(frame)
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
                        enqueue(ControlPacket.Pong(packet.seq).toWireBytes())
                    }
                    is ControlPacket.Pong -> pendingPong.get()?.complete(packet.seq)
                    is ControlPacket.Announce -> if (mode == Mode.GO_INBOUND) {
                        handleAnnounceOnGo(packet)
                    }
                    is ControlPacket.AnnounceOk -> pendingAnnounceOk.get()?.complete(packet)
                }
            }
        } finally {
            if (mode == Mode.GO_INBOUND) {
                closeQuietly()
            }
        }
    }

    private suspend fun handleAnnounceOnGo(packet: ControlPacket.Announce) {
        if (packet.nodeId.identifier == localNodeId.identifier) return
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
        enqueue(ControlPacket.AnnounceOk(localNodeId, ourIp).toWireBytes())
        PctLog.event("link: announce_ok child=${packet.nodeId.name} local=$ourIp")
    }

    private fun socketAlive(): Boolean = !socket.isClosed && socket.isConnected

    private companion object {
        const val READ_TIMEOUT_MS = 15_000
        val PONG_WAIT = 8.seconds
        val ANNOUNCE_OK_WAIT = 8.seconds
        val PING_INTERVAL = 5.seconds
    }
}