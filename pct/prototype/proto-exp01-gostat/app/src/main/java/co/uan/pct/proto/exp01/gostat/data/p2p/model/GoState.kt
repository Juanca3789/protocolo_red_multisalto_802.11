package co.uan.pct.proto.exp01.gostat.data.p2p.model

sealed interface GoState {
    data object Idle : GoState
    data object Creating : GoState
    data class Ready(
        val ssid: String,
        val psk: String,
        val isGroupOwner: Boolean,
    ) : GoState

    data class Error(val message: String) : GoState
}
