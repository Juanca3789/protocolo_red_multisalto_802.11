package co.uan.pct.app.pctmesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import co.uan.pct.app.pctmesh.PctMeshApplication
import co.uan.pct.lib.core.MultiHopProtocol
import co.uan.pct.lib.core.types.Role
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class RadioProbeUiState(
    val role: Role = Role.ISLAND,
    val parentMac: String? = null,
    val childMacs: List<String> = emptyList(),
)

class RadioProbeViewModel(
    private val protocol: MultiHopProtocol,
) : ViewModel() {

    val uiState: StateFlow<RadioProbeUiState> = combine(
        protocol.state,
        protocol.parentMac,
        protocol.connectedMacs,
    ) { role, parentMac, childMacs ->
        RadioProbeUiState(
            role = role,
            parentMac = parentMac,
            childMacs = childMacs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RadioProbeUiState(),
    )

    fun start() {
        protocol.start()
    }

    fun stop() {
        protocol.stop()
    }

    class Factory(
        private val app: PctMeshApplication,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RadioProbeViewModel::class.java))
            return RadioProbeViewModel(app.protocol) as T
        }
    }
}
