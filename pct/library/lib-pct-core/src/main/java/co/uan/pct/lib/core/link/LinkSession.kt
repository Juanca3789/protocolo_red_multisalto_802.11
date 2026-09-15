package co.uan.pct.lib.core.link

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Un TCP de control `:8765` con un vecino.
 *
 * [isParent] lo fija el sentido del socket: si yo conecté (tenía la STA), el vecino es mi padre;
 * si lo acepté en mi GO, es mi hijo. No hay negociación de sentido.
 */
internal class LinkSession(
    val socket: Socket,
    val isParent: Boolean,
) {
    val remoteAddress: InetAddress = socket.inetAddress
    val remoteIp: String = remoteAddress.hostAddress ?: remoteAddress.toString()

    var peerNid: String? = null
    var peerDepth: Int = 0
    var peerTree: String? = null

    @Volatile
    var lastRxMs: Long = System.currentTimeMillis()

    @Volatile
    var dataOpen: Boolean = false

    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val pingSeq = AtomicInteger(0)

    @Synchronized
    fun send(msg: CtrlMsg) {
        writer.write(CtrlCodec.encode(msg))
        writer.write('\n'.code)
        writer.flush()
    }

    /** Siguiente línea decodificada; null al cerrarse el socket. Líneas ajenas se ignoran. */
    fun read(): CtrlMsg? {
        while (true) {
            val line = try {
                reader.readLine()
            } catch (_: IOException) {
                return null
            } ?: return null
            lastRxMs = System.currentTimeMillis()
            CtrlCodec.decode(line)?.let { return it }
        }
    }

    fun nextPing(): CtrlMsg.Ping = CtrlMsg.Ping(pingSeq.incrementAndGet())

    fun close() {
        runCatching { socket.close() }
    }
}
