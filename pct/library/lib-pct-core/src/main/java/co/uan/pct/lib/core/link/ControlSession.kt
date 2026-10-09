package co.uan.pct.lib.core.link

import android.net.ConnectivityManager
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.types.NodeId
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Una sesión TCP :8765 con cola de salida única y lector dedicado.
 */
@OptIn(ExperimentalUuidApi::class)
class ControlSession(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val localNodeId: NodeId,
    private val neighborTable: NeighborTable,
    private val connectivity: ConnectivityManager,
    private val mode: Mode,
    private val onClosed: (() -> Unit)? = null,
) {
    enum class Mode {
        /** Hijo → padre por STA. */
        UPSTREAM,
        /** Padre acepta en GO. */
        GO_INBOUND,
    }

    private val outbound = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val nextSeq = AtomicInteger(1)
    private val pendingPong = AtomicReference<CompletableDeferred<Int>?>(null)
    private val pendingAnnounceOk = AtomicReference<CompletableDeferred<ControlCodec.NodePayload>?>(null)

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

    /** PING→PONG, luego ANNOUNCE→ANNOUNCE_OK; mantiene keep-alive PING. */
    suspend fun runUpstreamJoin(staLocalIpv4: String) {
        pingAwaitPong()
        announceAwaitOk(staLocalIpv4)
        keepAliveJob = scope.launch {
            while (scope.isActive && socketAlive()) {
                kotlinx.coroutines.delay(PING_INTERVAL)
                runCatching { pingAwaitPong() }
                    .onFailure { t ->
                        PctLog.event("l2: upstream keepalive fallo ${t.message}")
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
        enqueue(ControlCodec.encodePing(seq))
        withTimeout(PONG_WAIT) {
            val got = ack.await()
            if (got != seq) error("pong seq mismatch")
        }
        pendingPong.set(null)
    }

    private suspend fun announceAwaitOk(staLocalIpv4: String) {
        require(mode == Mode.UPSTREAM)
        if (!socketAlive()) error("socket dead")
        val ack = CompletableDeferred<ControlCodec.NodePayload>()
        pendingAnnounceOk.set(ack)
        enqueue(ControlCodec.encodeAnnounce(localNodeId, staLocalIpv4))
        PctLog.event("l2: announce upstream ip=$staLocalIpv4")
        withTimeout(ANNOUNCE_OK_WAIT) {
            val parent = ack.await()
            if (parent.nodeId.identifier == localNodeId.identifier) return@withTimeout
            neighborTable.addEntry(parent.nodeId, hops = 1)
            PctLog.event(
                "l2: announce_ok parent=${parent.nodeId.name} " +
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
                PctLog.event("l2: control write fallo ${t.message}")
                break
            }
        }
    }

    private suspend fun readerLoop() {
        val input = socket.getInputStream()
        try {
            while (scope.isActive && socketAlive()) {
                val frame = runCatching { ControlCodec.decode(input) }.getOrNull() ?: break
                when (frame) {
                is ControlCodec.Frame.Ping -> if (mode == Mode.GO_INBOUND) {
                    enqueue(ControlCodec.encodePong(frame.seq))
                }
                is ControlCodec.Frame.Pong -> {
                    pendingPong.get()?.complete(frame.seq)
                }
                is ControlCodec.Frame.Announce -> if (mode == Mode.GO_INBOUND) {
                    handleAnnounceOnGo(frame.payload)
                }
                is ControlCodec.Frame.AnnounceOk -> {
                    pendingAnnounceOk.get()?.complete(frame.payload)
                }
                }
            }
        } finally {
            if (mode == Mode.GO_INBOUND) {
                closeQuietly()
            }
        }
    }

    private suspend fun handleAnnounceOnGo(payload: ControlCodec.NodePayload) {
        if (payload.nodeId.identifier == localNodeId.identifier) return
        neighborTable.addEntry(payload.nodeId, hops = 1)
        PctLog.event(
            "l2: announce child=${payload.nodeId.name} " +
                "id=${payload.nodeId.identifier} ip=${payload.ipv4OnLink} hops=1",
        )
        val goIp = GoSoftAp.hostIpv4ForAnnounceOk(connectivity)
        if (goIp == null) {
            PctLog.event("l2: announce_ok omitido (sin ipv4 go)")
            return
        }
        enqueue(ControlCodec.encodeAnnounceOk(localNodeId, goIp))
        PctLog.event("l2: announce_ok go ip=$goIp")
    }

    private fun socketAlive(): Boolean =
        !socket.isClosed && socket.isConnected

    private companion object {
        const val READ_TIMEOUT_MS = 15_000
        val PONG_WAIT = 8.seconds
        val ANNOUNCE_OK_WAIT = 8.seconds
        val PING_INTERVAL = 5.seconds
    }
}
