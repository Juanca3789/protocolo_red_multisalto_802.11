package co.uan.pct.proto.exp01.gostat.data.p2p.model

data class PctCtrlCandidate(
    val record: PctCtrlRecord,
    val deviceName: String,
    val deviceAddress: String,
    val instanceName: String,
    val discoveredAtMs: Long,
)
