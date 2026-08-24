package co.uan.pct.lib.core.internal.tcp

internal data class PctFrame(
    val msgType: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PctFrame) return false
        return msgType == other.msgType && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = msgType
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

internal data class HelloPayload(
    val senderNid: String,
    val role: Int,
    val parentNid: String,
    val epoch: Int,
    val treeVersion: Int,
    val hop: Int,
    val capabilities: Int = 0,
    val neighborCount: Int = 0,
)

internal data class JoinCommitPayload(
    val committerNid: String,
    val offerId: Int = 0,
    val epoch: Int = 1,
    val via: Int = 0,
)

internal data class PingPayload(
    val senderNid: String,
    val seq: Int,
)

internal data class DataChannelOpenPayload(
    val neighborNid: String,
    val dataPort: Int,
    val epoch: Int = 1,
)

internal data class DataChannelAckPayload(
    val neighborNid: String,
    val status: Int = 0,
)

internal data class TopoRouteEntry(
    val destNid: String,
    val hopCount: Int,
    val status: Int,
    val pathSeq: Int,
)

internal data class TopoUpdatePayload(
    val originNid: String,
    val pathSeq: Int,
    val epoch: Int,
    val ttl: Int,
    val entries: List<TopoRouteEntry>,
)

internal data class UserDataPayload(
    val msgId: String,
    val srcNid: String,
    val dstNid: String,
    val sessionEpoch: Int,
    val hopLimit: Int,
    val trace: List<String>,
    val userData: ByteArray,
)
