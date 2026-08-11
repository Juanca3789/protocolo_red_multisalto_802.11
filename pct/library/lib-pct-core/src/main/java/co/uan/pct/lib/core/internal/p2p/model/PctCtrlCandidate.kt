package co.uan.pct.lib.core.internal.p2p.model

data class PctCtrlCandidate(
    val record: PctCtrlRecord,
    val deviceName: String,
    val deviceAddress: String,
    val instanceName: String,
    val discoveredAtMs: Long,
)
