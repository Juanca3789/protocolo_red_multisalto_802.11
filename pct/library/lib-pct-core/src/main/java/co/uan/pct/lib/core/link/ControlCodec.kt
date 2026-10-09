package co.uan.pct.lib.core.link

import co.uan.pct.lib.core.types.NodeId
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Frames de control en :8765 (big-endian).
 *
 * - **PCTG** + seq 4 — PING
 * - **PCTP** + seq 4 — PONG
 * - **PCTA** + node — ANNOUNCE
 * - **PCTO** + node — ANNOUNCE_OK
 *
 * Cuerpo node: | uuid 16 | name_len 2 | name utf-8 | ipv4 4 |
 */
@OptIn(ExperimentalUuidApi::class)
object ControlCodec {
    private val MAGIC_PING = byteArrayOf(0x50, 0x43, 0x54, 0x47) // PCTG
    private val MAGIC_PONG = byteArrayOf(0x50, 0x43, 0x54, 0x50) // PCTP
    private val MAGIC_ANNOUNCE = byteArrayOf(0x50, 0x43, 0x54, 0x41) // PCTA
    private val MAGIC_ANNOUNCE_OK = byteArrayOf(0x50, 0x43, 0x54, 0x4F) // PCTO

    private const val UUID_BYTES = 16
    private const val MAX_NAME_BYTES = 512

    data class NodePayload(
        val nodeId: NodeId,
        val ipv4OnLink: String,
    )

    sealed class Frame {
        data class Ping(val seq: Int) : Frame()
        data class Pong(val seq: Int) : Frame()
        data class Announce(val payload: NodePayload) : Frame()
        data class AnnounceOk(val payload: NodePayload) : Frame()
    }

    fun encodePing(seq: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC_PING)
            putInt(seq)
        }.array()

    fun encodePong(seq: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC_PONG)
            putInt(seq)
        }.array()

    fun encodeAnnounce(nodeId: NodeId, ipv4: String): ByteArray =
        encodeNodeFrame(MAGIC_ANNOUNCE, nodeId, ipv4)

    fun encodeAnnounceOk(nodeId: NodeId, ipv4: String): ByteArray =
        encodeNodeFrame(MAGIC_ANNOUNCE_OK, nodeId, ipv4)

    fun decode(input: InputStream): Frame? {
        val magic = readFully(input, 4) ?: return null
        return when {
            magic.contentEquals(MAGIC_PING) -> {
                val seq = readIntBe(input) ?: return null
                Frame.Ping(seq)
            }
            magic.contentEquals(MAGIC_PONG) -> {
                val seq = readIntBe(input) ?: return null
                Frame.Pong(seq)
            }
            magic.contentEquals(MAGIC_ANNOUNCE) -> {
                val payload = readNodePayload(input) ?: return null
                Frame.Announce(payload)
            }
            magic.contentEquals(MAGIC_ANNOUNCE_OK) -> {
                val payload = readNodePayload(input) ?: return null
                Frame.AnnounceOk(payload)
            }
            else -> null
        }
    }

    fun decode(frame: ByteArray): Frame? =
        decode(java.io.ByteArrayInputStream(frame))

    private fun encodeNodeFrame(magic: ByteArray, nodeId: NodeId, ipv4: String): ByteArray {
        val nameBytes = nodeId.name.toByteArray(StandardCharsets.UTF_8)
        require(nameBytes.size in 1..MAX_NAME_BYTES) { "name length out of range" }
        val ipBytes = parseIpv4(ipv4) ?: throw IllegalArgumentException("invalid ipv4: $ipv4")
        val uuidBytes = nodeId.identifier.toByteArray()
        require(uuidBytes.size == UUID_BYTES)

        return ByteBuffer.allocate(magic.size + UUID_BYTES + 2 + nameBytes.size + 4)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(magic)
                put(uuidBytes)
                putShort(nameBytes.size.toShort())
                put(nameBytes)
                put(ipBytes)
            }
            .array()
    }

    private fun readNodePayload(input: InputStream): NodePayload? {
        val uuidBytes = readFully(input, UUID_BYTES) ?: return null
        val uuid = runCatching { Uuid.fromByteArray(uuidBytes) }.getOrNull() ?: return null
        val lenBuf = readFully(input, 2) ?: return null
        val nameLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        if (nameLen !in 1..MAX_NAME_BYTES) return null
        val nameBytes = readFully(input, nameLen) ?: return null
        val name = runCatching { String(nameBytes, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val ipBytes = readFully(input, 4) ?: return null
        val ip = formatIpv4(ipBytes) ?: return null
        return NodePayload(NodeId(uuid, name), ip)
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

    private fun parseIpv4(text: String): ByteArray? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n.toByte()
        }
        return out
    }

    private fun formatIpv4(bytes: ByteArray): String? {
        if (bytes.size != 4) return null
        return "${bytes[0].toUByte()}.${bytes[1].toUByte()}.${bytes[2].toUByte()}.${bytes[3].toUByte()}"
    }
}
