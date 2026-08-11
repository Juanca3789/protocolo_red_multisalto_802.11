package co.uan.pct.lib.core.internal.p2p.model

data class PctCtrlRecord(
    val nid: String,
    val role: String,
    val hop: Int,
    val epoch: Long,
    val ctrlPort: Int,
    val goSsid: String,
    val goPsk: String,
    val deviceAddress: String,
)
