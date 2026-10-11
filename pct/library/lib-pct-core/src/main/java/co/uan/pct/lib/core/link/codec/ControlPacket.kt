package co.uan.pct.lib.core.link.codec

import co.uan.pct.lib.core.types.NodeId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.uuid.ExperimentalUuidApi

/**
 * Paquete de control link (:8765). Cuerpo node: | uuid 16 | name_len 2 | name utf-8 | ipv4 4 |
 */
@OptIn(ExperimentalUuidApi::class)
sealed class ControlPacket {

    abstract fun toWireBytes(): ByteArray

    data class Ping(val seq: Int) : ControlPacket() {
        override fun toWireBytes(): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
                put(ControlPacketKind.PING.magic)
                putInt(seq)
            }.array()
    }

    data class Pong(val seq: Int) : ControlPacket() {
        override fun toWireBytes(): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
                put(ControlPacketKind.PONG.magic)
                putInt(seq)
            }.array()
    }

    data class Announce(
        val nodeId: NodeId,
        val ipv4OnLink: String,
    ) : ControlPacket() {
        override fun toWireBytes(): ByteArray =
            encodeNodeBody(ControlPacketKind.ANNOUNCE, nodeId, ipv4OnLink)
    }

    data class AnnounceOk(
        val nodeId: NodeId,
        val ipv4OnLink: String,
    ) : ControlPacket() {
        override fun toWireBytes(): ByteArray =
            encodeNodeBody(ControlPacketKind.ANNOUNCE_OK, nodeId, ipv4OnLink)
    }

    internal companion object {
        private const val UUID_BYTES = 16
        private const val MAX_NAME_BYTES = 512

        fun encodeNodeBody(kind: ControlPacketKind, nodeId: NodeId, ipv4: String): ByteArray {
            val nameBytes = nodeId.name.toByteArray(StandardCharsets.UTF_8)
            require(nameBytes.size in 1..MAX_NAME_BYTES) { "name length out of range" }
            val ipBytes = parseIpv4(ipv4) ?: throw IllegalArgumentException("invalid ipv4: $ipv4")
            val uuidBytes = nodeId.identifier.toByteArray()
            require(uuidBytes.size == UUID_BYTES)
            return ByteBuffer.allocate(kind.magic.size + UUID_BYTES + 2 + nameBytes.size + 4)
                .order(ByteOrder.BIG_ENDIAN)
                .apply {
                    put(kind.magic)
                    put(uuidBytes)
                    putShort(nameBytes.size.toShort())
                    put(nameBytes)
                    put(ipBytes)
                }
                .array()
        }

        fun parseIpv4(text: String): ByteArray? {
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

    }
}
