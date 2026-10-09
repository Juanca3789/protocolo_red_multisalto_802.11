package co.uan.pct.app.pctmesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import co.uan.pct.app.pctmesh.PctMeshApplication
import co.uan.pct.lib.core.MultiHopProtocol
import co.uan.pct.lib.core.types.Role
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class NeighborRowUi(
    val shortId: String,
    val name: String,
    val hops: Int,
)

data class RadioProbeUiState(
    val role: Role = Role.ISLAND,
    val parentMac: String? = null,
    val childMacs: List<String> = emptyList(),
    val neighborRows: List<NeighborRowUi> = emptyList(),
)

@OptIn(ExperimentalUuidApi::class)
class RadioProbeViewModel(
    private val protocol: MultiHopProtocol,
) : ViewModel() {

    val uiState: StateFlow<RadioProbeUiState> = combine(
        protocol.state,
        protocol.parentMac,
        protocol.connectedMacs,
        protocol.neighborTable,
    ) { role, parentMac, childMacs, neighborTable ->
        RadioProbeUiState(
            role = role,
            parentMac = parentMac,
            childMacs = childMacs,
            neighborRows = neighborTable
                .sortedWith(compareBy({ it.second }, { it.first.name.lowercase() }))
                .map { (nodeId, hops) ->
                    NeighborRowUi(
                        shortId = nodeId.identifier.toString().take(8),
                        name = nodeId.name,
                        hops = hops,
                    )
                },
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
