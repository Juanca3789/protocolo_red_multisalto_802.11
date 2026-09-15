package co.uan.pct.lib.core.link

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

internal class LinkSession(
    val socket: Socket,
    val remoteIp: String,
) {
    /** Dirección remota con ámbito (para `fe80::%iface`); es la que se usa para abrir datos. */
    val remoteAddress: InetAddress = socket.inetAddress

    fun sameHost(other: InetAddress): Boolean =
        remoteAddress.address.contentEquals(other.address)

    var peerNid: String? = null
    var peerDepth: Int = 0
    var peerTree: String? = null
    var lastRxMs: Long = System.currentTimeMillis()
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

    fun read(): CtrlMsg? {
        val line = try {
            reader.readLine()
        } catch (_: IOException) {
            return null
        } ?: return null
        lastRxMs = System.currentTimeMillis()
        return CtrlCodec.decode(line)
    }

    fun nextPing(): CtrlMsg.Ping = CtrlMsg.Ping(pingSeq.incrementAndGet())

    fun close() {
        runCatching { socket.close() }
    }
}
