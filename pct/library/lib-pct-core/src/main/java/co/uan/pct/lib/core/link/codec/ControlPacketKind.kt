package co.uan.pct.lib.core.link.codec

import co.uan.pct.lib.core.types.NodeId
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Tipos de paquete de control en :8765 (magic 4 ASCII, big-endian). */
enum class ControlPacketKind(val magic: ByteArray) {
    PING(byteArrayOf(0x50, 0x43, 0x54, 0x47)), // PCTG
    PONG(byteArrayOf(0x50, 0x43, 0x54, 0x50)), // PCTP
    ANNOUNCE(byteArrayOf(0x50, 0x43, 0x54, 0x41)), // PCTA
    ANNOUNCE_OK(byteArrayOf(0x50, 0x43, 0x54, 0x4F)), // PCTO
    REACH(byteArrayOf(0x50, 0x43, 0x54, 0x52)), // PCTR
    UNREACH(byteArrayOf(0x50, 0x43, 0x54, 0x55)), // PCTU
    ;

    companion object {
        fun fromMagic(magic: ByteArray): ControlPacketKind? =
            entries.firstOrNull { it.magic.contentEquals(magic) }
    }
}

@OptIn(ExperimentalUuidApi::class)
fun readControlPacket(input: InputStream): ControlPacket? {
    val magic = readFully(input, 4) ?: return null
    return when (ControlPacketKind.fromMagic(magic)) {
        ControlPacketKind.PING -> readIntBe(input)?.let { ControlPacket.Ping(it) }
        ControlPacketKind.PONG -> readIntBe(input)?.let { ControlPacket.Pong(it) }
        ControlPacketKind.ANNOUNCE -> readNodeBody(input)?.let { (id, ip) ->
            ControlPacket.Announce(id, ip)
        }
        ControlPacketKind.ANNOUNCE_OK -> readNodeBody(input)?.let { (id, ip) ->
            ControlPacket.AnnounceOk(id, ip)
        }
        ControlPacketKind.REACH -> readReachBody(input)
        ControlPacketKind.UNREACH -> readUnreachBody(input)
        null -> null
    }
}

fun readControlPacket(frame: ByteArray): ControlPacket? =
    readControlPacket(frame.inputStream())

private const val UUID_BYTES = 16
private const val MAX_NAME_BYTES = 512

@OptIn(ExperimentalUuidApi::class)
private fun readNodeBody(input: InputStream): Pair<NodeId, String>? {
    val uuidBytes = readFully(input, UUID_BYTES) ?: return null
    val uuid = runCatching { Uuid.fromByteArray(uuidBytes) }.getOrNull() ?: return null
    val lenBuf = readFully(input, 2) ?: return null
    val nameLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
    if (nameLen !in 1..MAX_NAME_BYTES) return null
    val nameBytes = readFully(input, nameLen) ?: return null
    val name = runCatching { String(nameBytes, StandardCharsets.UTF_8) }.getOrNull() ?: return null
    val ipBytes = readFully(input, 4) ?: return null
    val ip = formatIpv4(ipBytes) ?: return null
    return NodeId(uuid, name) to ip
}

@OptIn(ExperimentalUuidApi::class)
private fun readReachBody(input: InputStream): ControlPacket.Reach? {
    val uuidBytes = readFully(input, UUID_BYTES) ?: return null
    val uuid = runCatching { Uuid.fromByteArray(uuidBytes) }.getOrNull() ?: return null
    val lenBuf = readFully(input, 2) ?: return null
    val nameLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
    if (nameLen !in 1..MAX_NAME_BYTES) return null
    val nameBytes = readFully(input, nameLen) ?: return null
    val name = runCatching { String(nameBytes, StandardCharsets.UTF_8) }.getOrNull() ?: return null
    val hopsBuf = readFully(input, 1) ?: return null
    val hops = hopsBuf[0].toInt() and 0xFF
    if (hops < 1) return null
    return ControlPacket.Reach(NodeId(uuid, name), hops)
}

@OptIn(ExperimentalUuidApi::class)
private fun readUnreachBody(input: InputStream): ControlPacket.Unreach? {
    val uuidBytes = readFully(input, UUID_BYTES) ?: return null
    val uuid = runCatching { Uuid.fromByteArray(uuidBytes) }.getOrNull() ?: return null
    return ControlPacket.Unreach(uuid)
}

private fun readIntBe(input: InputStream): Int? {
    val buf = readFully(input, 4) ?: return null
    return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).int
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

private fun formatIpv4(bytes: ByteArray): String? {
    if (bytes.size != 4) return null
    return "${bytes[0].toUByte()}.${bytes[1].toUByte()}.${bytes[2].toUByte()}.${bytes[3].toUByte()}"
}
