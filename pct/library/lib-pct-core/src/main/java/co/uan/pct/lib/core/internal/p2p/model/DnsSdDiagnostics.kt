package co.uan.pct.lib.core.internal.p2p.model

data class DnsSdDiagnostics(
    val phase: DiscoveryPhase = DiscoveryPhase.Idle,
    val peerCount: Int = 0,
    val servicesSeen: Int = 0,
    val pctCtrlSeen: Int = 0,
    val discoveryTicks: Int = 0,
    val broadFilter: Boolean = false,
    val txtCallbacks: Int = 0,
    val hint: String = "",
)

enum class DiscoveryPhase {
    Idle,
    DiscoveringPeers,
    WaitingPeers,
    RegisteringRequest,
    DiscoveringServices,
    ServiceFound,
    TxtReady,
    Failed,
}
