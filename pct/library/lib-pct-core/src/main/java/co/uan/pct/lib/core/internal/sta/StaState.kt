package co.uan.pct.lib.core.internal.sta

sealed interface StaState {
    data object Idle : StaState
    data object Connecting : StaState
    data class Connected(val ssid: String) : StaState
    data class Error(val message: String) : StaState
}
