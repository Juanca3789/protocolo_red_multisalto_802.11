package co.uan.pct.lib.core.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream

data class UserFrame(
    val src: String,
    val dst: String,
    val ttl: Int,
    val payload: ByteArray,
) {
    fun hop(): UserFrame = copy(ttl = ttl - 1)
}

data class UserMessage(
    val from: String,
    val payload: ByteArray,
)

object UserCodec {
    const val MAGIC = 0x50435455
    const val TTL = 7

    fun encode(frame: UserFrame): ByteArray {
        val payload = frame.payload
        require(payload.size <= 0xFFFF)
        val out = ByteArrayOutputStream(HEADER + payload.size)
        DataOutputStream(out).use { data ->
            data.writeInt(MAGIC)
            data.write(nid16(frame.src))
            data.write(nid16(frame.dst))
            data.writeByte(frame.ttl.coerceIn(0, 255))
            data.writeShort(payload.size)
            data.write(payload)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): UserFrame? = read(ByteArrayInputStream(bytes))

    fun read(input: InputStream): UserFrame? = runCatching {
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) return null
        val src = ByteArray(16)
        val dst = ByteArray(16)
        data.readFully(src)
        data.readFully(dst)
        val ttl = data.readUnsignedByte()
        val len = data.readUnsignedShort()
        val payload = ByteArray(len)
        data.readFully(payload)
        UserFrame(hex(src), hex(dst), ttl, payload)
    }.getOrNull()

    fun sameNid(a: String, b: String): Boolean {
        val left = a.lowercase().replace("-", "").padEnd(32, '0').take(32)
        val right = b.lowercase().replace("-", "").padEnd(32, '0').take(32)
        return left == right || left.take(8) == right.take(8)
    }

    fun nid16(nid: String): ByteArray {
        val hex = nid.lowercase().replace("-", "").padEnd(32, '0').take(32)
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { b -> "%02x".format(b) }

    private const val HEADER = 4 + 16 + 16 + 1 + 2
}
