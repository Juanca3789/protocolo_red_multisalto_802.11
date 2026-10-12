package co.uan.pct.lib.core.data.tcp

import android.util.Log
import co.uan.pct.lib.core.data.codec.DataPacket
import co.uan.pct.lib.core.data.codec.readDataPacket
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
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Un TCP :8766: cola de salida + lector. Primero HELLO en ambos sentidos (solo vecinos en tabla);
 * después BCAST / BCAST_RECV. No interpreta el broadcast: lo entrega a [onBcast].
 */
@OptIn(ExperimentalUuidApi::class)
internal class DataTcpSession(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val localNodeId: NodeId,
    private val mode: Mode,
    private val acceptPeer: (Uuid) -> Boolean,
    private val onBcast: (DataTcpSession, DataPacket.Bcast) -> Unit,
    private val onUnicast: (DataTcpSession, DataPacket.Unicast) -> Unit,
    private val onClosed: ((DataTcpSession) -> Unit)? = null,
) {
    enum class Mode {
        UPSTREAM,
        GO_INBOUND,
    }

    private val outbound = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val peerHello = CompletableDeferred<Uuid>()

    private var writerJob: Job? = null
    private var readerJob: Job? = null

    @Volatile
    var peerUuid: Uuid? = null
        private set

    @Volatile
    private var closed = false

    val isIdentified: Boolean
        get() = peerUuid != null && socketAlive()

    fun start() {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        writerJob = scope.launch { writerLoop() }
        readerJob = scope.launch { readerLoop() }
    }

    fun closeQuietly() {
        if (closed) return
        closed = true
        readerJob?.cancel()
        writerJob?.cancel()
        outbound.close()
        runCatching { socket.close() }
        onClosed?.invoke(this)
    }

    /** Hijo: manda HELLO y espera el HELLO del padre; el padre debe estar en tabla. */
    suspend fun runUpstreamHello(): Uuid {
        require(mode == Mode.UPSTREAM)
        if (!socketAlive()) error("socket dead")
        enqueue(DataPacket.Hello(localNodeId.identifier).toWireBytes())
        val parent = withTimeout(HELLO_WAIT) { peerHello.await() }
        if (!acceptPeer(parent)) error("parent $parent not in table")
        peerUuid = parent
        PctLog.event("data: hello upstream parent=$parent")
        return parent
    }

    /** Encola un frame ya serializado; se ignora si la sesión está cerrada. */
    fun enqueue(frame: ByteArray) {
        if (!socketAlive()) return
        outbound.trySend(frame)
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
                PctLog.event("data: write fallo ${t.message}")
                break
            }
        }
        closeQuietly()
    }

    private suspend fun readerLoop() {
        val input = socket.getInputStream()
        try {
            while (scope.isActive && socketAlive()) {
                when (val packet = runCatching { readDataPacket(input) }.getOrNull()) {
                    null -> break
                    is DataPacket.Hello -> handleHello(packet)
                    is DataPacket.Bcast -> {
                        if (peerUuid == null) {
                            PctLog.event("data: bcast de sesión sin hello, cierro")
                            break
                        }
                        onBcast(this, packet)
                    }
                    is DataPacket.BcastRecv -> PctLog.emit(
                        LOG_TAG,
                        Log.DEBUG,
                        "recv ack msg=${packet.msgId} origin=${packet.originUuid} peer=$peerUuid",
                    )
                    is DataPacket.Unicast -> {
                        if (peerUuid == null) {
                            PctLog.event("data: unicast de sesión sin hello, cierro")
                            break
                        }
                        onUnicast(this, packet)
                    }
                    is DataPacket.UnicastRecv -> PctLog.emit(
                        LOG_TAG,
                        Log.DEBUG,
                        "unicast ack msg=${packet.msgId} origin=${packet.originUuid} peer=$peerUuid",
                    )
                }
            }
        } finally {
            closeQuietly()
        }
    }

    private suspend fun handleHello(packet: DataPacket.Hello) {
        when (mode) {
            Mode.UPSTREAM -> peerHello.complete(packet.nodeUuid)
            Mode.GO_INBOUND -> {
                if (packet.nodeUuid == localNodeId.identifier) {
                    PctLog.event("data: hello de mi propio uuid, cierro")
                    closeQuietly()
                    return
                }
                // ANNOUNCE en :8765 y este HELLO pueden llegar en el mismo instante.
                val inTable = waitUntilPeerInTable(packet.nodeUuid)
                if (!inTable) {
                    PctLog.event("data: hello child=${packet.nodeUuid} no está en tabla, cierro")
                    closeQuietly()
                    return
                }
                peerUuid = packet.nodeUuid
                enqueue(DataPacket.Hello(localNodeId.identifier).toWireBytes())
                PctLog.event("data: hello child=${packet.nodeUuid}")
            }
        }
    }

    private suspend fun waitUntilPeerInTable(uuid: Uuid): Boolean {
        val deadline = System.nanoTime() + HELLO_TABLE_GRACE.inWholeNanoseconds
        while (socketAlive()) {
            if (acceptPeer(uuid)) return true
            if (System.nanoTime() >= deadline) return false
            delay(50)
        }
        return false
    }

    private fun socketAlive(): Boolean = !closed && !socket.isClosed && socket.isConnected

    private companion object {
        const val LOG_TAG = "DataTcpSession"
        val HELLO_WAIT = 8.seconds
        val HELLO_TABLE_GRACE = 2.seconds
    }
}
