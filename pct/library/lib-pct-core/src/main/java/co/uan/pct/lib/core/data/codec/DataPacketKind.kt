package co.uan.pct.lib.core.data.codec

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tipos de paquete de datos en :8766. Magic común `PCTD` (4 bytes) + `msg_type` en el byte 5.
 * Desde el byte 5 ya se sabe qué cuerpo esperar; no hay versión. `dest_uuid` solo en UNICAST.
 */
enum class DataPacketKind(val code: Byte) {
    HELLO(0x00),
    BCAST(0x01),
    BCAST_RECV(0x02),
    UNICAST(0x03),
    UNICAST_RECV(0x04),
    ;

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x50, 0x43, 0x54, 0x44) // PCTD

        fun fromCode(code: Byte): DataPacketKind? = entries.firstOrNull { it.code == code }
    }
}

@OptIn(ExperimentalUuidApi::class)
fun readDataPacket(input: InputStream): DataPacket? {
    val magic = readFully(input, 4) ?: return null
    if (!magic.contentEquals(DataPacketKind.MAGIC)) return null
    val code = readFully(input, 1) ?: return null
    return when (DataPacketKind.fromCode(code[0])) {
        DataPacketKind.HELLO -> readUuid(input)?.let { DataPacket.Hello(it) }
        DataPacketKind.BCAST -> readBcastBody(input)
        DataPacketKind.BCAST_RECV -> {
            val msgId = readLongBe(input) ?: return null
            val origin = readUuid(input) ?: return null
            DataPacket.BcastRecv(msgId, origin)
        }
        DataPacketKind.UNICAST -> readUnicastBody(input)
        DataPacketKind.UNICAST_RECV -> {
            val msgId = readLongBe(input) ?: return null
            val origin = readUuid(input) ?: return null
            DataPacket.UnicastRecv(msgId, origin)
        }
        null -> null
    }
}

fun readDataPacket(frame: ByteArray): DataPacket? =
    readDataPacket(frame.inputStream())

private const val UUID_BYTES = 16

@OptIn(ExperimentalUuidApi::class)
private fun readUnicastBody(input: InputStream): DataPacket.Unicast? {
    val msgId = readLongBe(input) ?: return null
    val origin = readUuid(input) ?: return null
    val dest = readUuid(input) ?: return null
    val tsMs = readLongBe(input) ?: return null
    val lenBuf = readFully(input, 2) ?: return null
    val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
    val payload = if (len == 0) ByteArray(0) else readFully(input, len) ?: return null
    return DataPacket.Unicast(msgId, origin, dest, tsMs, payload)
}

@OptIn(ExperimentalUuidApi::class)
private fun readBcastBody(input: InputStream): DataPacket.Bcast? {
    val msgId = readLongBe(input) ?: return null
    val origin = readUuid(input) ?: return null
    val tsMs = readLongBe(input) ?: return null
    val lenBuf = readFully(input, 2) ?: return null
    val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
    val payload = if (len == 0) ByteArray(0) else readFully(input, len) ?: return null
    return DataPacket.Bcast(msgId, origin, tsMs, payload)
}

@OptIn(ExperimentalUuidApi::class)
private fun readUuid(input: InputStream): Uuid? {
    val bytes = readFully(input, UUID_BYTES) ?: return null
    return runCatching { Uuid.fromByteArray(bytes) }.getOrNull()
}

private fun readLongBe(input: InputStream): Long? {
    val buf = readFully(input, 8) ?: return null
    return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).long
}

private fun readFully(input: InputStream, size: Int): ByteArray? {
    val buf = ByteArray(size)
    var off = 0
    while (off < size) {
        val n = input.read(buf, off, size - off)
        if (n <= 0) return null
        off += n
    }
    return buf
}
