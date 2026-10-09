package co.uan.pct.lib.core.radio.advertise

data class AndroidDnsSdService(
    val instanceName: String,
    val serviceType: String,
    val txtRecord: Map<String, String>,
)