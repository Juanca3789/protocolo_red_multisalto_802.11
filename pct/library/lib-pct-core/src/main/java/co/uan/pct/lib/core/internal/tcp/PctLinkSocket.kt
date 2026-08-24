package co.uan.pct.lib.core.internal.tcp

import android.net.Network
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Socket PCT con framing PCT1. Outbound: connect directo; inbound: adopción vía [adoptInbound].
 */
internal abstract class PctLinkSocket(
    private val allowedTypes: Set<Int>,
) : Socket() {
    private var adopted: Socket? = null

    protected fun connectOutbound(host: String, port: Int, network: Network?) {
        if (network != null) {
            network.bindSocket(this)
        }
        soTimeout = 0
        connect(InetSocketAddress(host, port), 15_000)
    }

    protected fun adoptInbound(socket: Socket) {
        adopted = socket
        soTimeout = 0
    }

    override fun getInputStream(): InputStream = adopted?.inputStream ?: super.getInputStream()

    override fun getOutputStream(): OutputStream = adopted?.outputStream ?: super.getOutputStream()

    override fun isClosed(): Boolean = adopted?.isClosed ?: super.isClosed()

    override fun close() {
        runCatching { adopted?.close() }
        adopted = null
        if (!super.isClosed) {
            runCatching { super.close() }
        }
    }

    @Throws(IOException::class)
    fun readFrame(): PctFrame {
        val header = ByteArray(PctFrameCodec.HEADER_SIZE)
        readFully(header)
        val (msgType, payloadLen) = PctFrameCodec.decodeHeader(header)
        if (msgType !in allowedTypes) {
            throw IOException("msg_type 0x${msgType.toString(16)} not allowed on this socket")
        }
        val payload = if (payloadLen == 0) {
            ByteArray(0)
        } else {
            ByteArray(payloadLen).also { readFully(it) }
        }
        return PctFrame(msgType, payload)
    }

    @Throws(IOException::class)
    fun writeFrame(msgType: Int, payload: ByteArray) {
        if (msgType !in allowedTypes) {
            throw IOException("msg_type 0x${msgType.toString(16)} not allowed on this socket")
        }
        val bytes = PctFrameCodec.encode(msgType, payload)
        getOutputStream().write(bytes)
        getOutputStream().flush()
    }

    @Throws(IOException::class)
    fun writeRaw(frame: ByteArray) {
        getOutputStream().write(frame)
        getOutputStream().flush()
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        val input = getInputStream()
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("EOF reading frame")
            off += n
        }
    }
}

internal class PctControlSocket private constructor() : PctLinkSocket(PctMsgType.CONTROL_TYPES) {
    constructor(host: String, port: Int, network: Network? = null) : this() {
        connectOutbound(host, port, network)
    }

    internal constructor(adopted: Socket) : this() {
        adoptInbound(adopted)
    }
}

internal class PctDataSocket private constructor() : PctLinkSocket(PctMsgType.DATA_TYPES) {
    constructor(host: String, port: Int, network: Network? = null) : this() {
        connectOutbound(host, port, network)
    }

    internal constructor(adopted: Socket) : this() {
        adoptInbound(adopted)
    }
}

internal class PctControlServerSocket(port: Int) : java.net.ServerSocket() {
    init {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    fun acceptControl(): PctControlSocket = PctControlSocket(accept())
}

internal class PctDataServerSocket(port: Int) : java.net.ServerSocket() {
    init {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    fun acceptData(): PctDataSocket = PctDataSocket(accept())
}
