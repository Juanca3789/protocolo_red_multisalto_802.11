package co.uan.pct.lib.core.radio.advertise

data class ObservedCtrlService(
    val service: AndroidDnsSdService,
    val deviceAddress: String,
    /** Best-effort; 0 if the platform did not expose signal for this P2P peer. */
    val rssiDbm: Int = 0,
)
