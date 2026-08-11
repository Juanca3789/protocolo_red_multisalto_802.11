package co.uan.pct.lib.core.api

sealed interface PctEvent {
    data class PhaseChanged(val phase: NodePhase) : PctEvent
    data class Log(val message: String) : PctEvent
    data class TopologyChanged(val snapshot: TopologySnapshot) : PctEvent
    data class Error(val message: String) : PctEvent
}
