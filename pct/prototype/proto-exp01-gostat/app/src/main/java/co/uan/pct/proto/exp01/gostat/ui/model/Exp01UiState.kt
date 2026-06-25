package co.uan.pct.proto.exp01.gostat.ui.model

import co.uan.pct.proto.exp01.gostat.data.p2p.model.DnsSdDiagnostics
import co.uan.pct.proto.exp01.gostat.data.p2p.model.GoState
import co.uan.pct.proto.exp01.gostat.data.p2p.model.PctCtrlCandidate
import co.uan.pct.proto.exp01.gostat.data.p2p.model.PctCtrlRecord
import co.uan.pct.proto.exp01.gostat.data.sta.StaState

data class Exp01UiState(
    val phase: NodePhase = NodePhase.ISLAND,
    val goState: GoState = GoState.Idle,
    val parentCandidates: List<PctCtrlCandidate> = emptyList(),
    val selectedParentAddress: String? = null,
    val discoveredService: PctCtrlRecord? = null,
    val isAdvertising: Boolean = false,
    val isDiscovering: Boolean = false,
    val staState: StaState = StaState.Idle,
    val dnsSdDiagnostics: DnsSdDiagnostics = DnsSdDiagnostics(),
    val autoActivateAfterJoin: Boolean = true,
    val broadDiscovery: Boolean = false,
    val logs: List<String> = emptyList(),
)
