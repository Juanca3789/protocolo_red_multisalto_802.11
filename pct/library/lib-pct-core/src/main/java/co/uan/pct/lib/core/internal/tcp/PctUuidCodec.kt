package co.uan.pct.lib.core.internal.tcp

internal object PctUuidCodec {
    fun toBytes(nid32: String): ByteArray {
        require(nid32.length == 32) { "UUID nid must be 32 hex chars" }
        return ByteArray(16) { i ->
            nid32.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun fromBytes(bytes: ByteArray, offset: Int = 0): String {
        require(bytes.size >= offset + 16) { "need 16 bytes for UUID" }
        return buildString(32) {
            for (i in 0 until 16) {
                append(String.format("%02x", bytes[offset + i].toInt() and 0xFF))
            }
        }
    }

    fun zeroBytes(): ByteArray = ByteArray(16)
}
