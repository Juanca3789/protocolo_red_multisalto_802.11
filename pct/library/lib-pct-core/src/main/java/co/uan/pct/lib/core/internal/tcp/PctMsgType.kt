package co.uan.pct.lib.core.internal.tcp

internal object PctMsgType {
    const val HELLO: Int = 0x01
    const val JOIN_COMMIT: Int = 0x05
    const val TOPO_UPDATE: Int = 0x06
    const val PING: Int = 0x07
    const val PONG: Int = 0x08
    const val NODE_DOWN: Int = 0x09
    const val DATA: Int = 0x0A
    const val DATA_CHANNEL_OPEN: Int = 0x0D
    const val DATA_CHANNEL_ACK: Int = 0x0E
    const val DATA_CHANNEL_RESET: Int = 0x0F

    val CONTROL_TYPES: Set<Int> = setOf(
        HELLO, JOIN_COMMIT, TOPO_UPDATE, PING, PONG, NODE_DOWN,
        DATA_CHANNEL_OPEN, DATA_CHANNEL_ACK, DATA_CHANNEL_RESET,
    )

    val DATA_TYPES: Set<Int> = setOf(DATA)
}
