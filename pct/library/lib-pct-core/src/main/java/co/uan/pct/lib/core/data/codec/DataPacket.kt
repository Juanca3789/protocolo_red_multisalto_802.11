package co.uan.pct.lib.core.data.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Paquete de datos (:8766). Cabecera `PCTD` + `msg_type`; cuerpos:
 * - HELLO:      | uuid 16 |
 * - BCAST:      | msg_id 8 | origin_uuid 16 | ts_ms 8 | payload_len 2 | payload |
 * - BCAST_RECV: | msg_id 8 | origin_uuid 16 |
 */
@OptIn(ExperimentalUuidApi::class)
sealed class DataPacket {

    abstract fun toWireBytes(): ByteArray

    /** Identificación del vecino al abrir la sesión; solo se habla con quien está en tabla. */
    data class Hello(val nodeUuid: Uuid) : DataPacket() {
        override fun toWireBytes(): ByteArray =
            header(DataPacketKind.HELLO, UUID_BYTES).apply {
                put(nodeUuid.toByteArray())
            }.array()
    }

    class Bcast(
        val msgId: Long,
        val originUuid: Uuid,
        val tsMs: Long,
        val payload: ByteArray,
    ) : DataPacket() {
        init {
            require(payload.size <= MAX_PAYLOAD_BYTES) { "payload too large: ${payload.size}" }
        }

        override fun toWireBytes(): ByteArray =
            header(DataPacketKind.BCAST, 8 + UUID_BYTES + 8 + 2 + payload.size).apply {
                putLong(msgId)
                put(originUuid.toByteArray())
                putLong(tsMs)
                putShort(payload.size.toShort())
                put(payload)
            }.array()
    }

    data class BcastRecv(val msgId: Long, val originUuid: Uuid) : DataPacket() {
        override fun toWireBytes(): ByteArray =
            header(DataPacketKind.BCAST_RECV, 8 + UUID_BYTES).apply {
                putLong(msgId)
                put(originUuid.toByteArray())
            }.array()
    }

    companion object {
        private const val UUID_BYTES = 16
        const val MAX_PAYLOAD_BYTES = 0xFFFF

        private fun header(kind: DataPacketKind, bodySize: Int): ByteBuffer =
            ByteBuffer.allocate(DataPacketKind.MAGIC.size + 1 + bodySize)
                .order(ByteOrder.BIG_ENDIAN)
                .apply {
                    put(DataPacketKind.MAGIC)
                    put(kind.code)
                }
    }
}
