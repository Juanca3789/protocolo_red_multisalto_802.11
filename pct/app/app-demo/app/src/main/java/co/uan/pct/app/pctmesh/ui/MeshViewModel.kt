package co.uan.pct.app.pctmesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import co.uan.pct.app.pctmesh.PctMeshApplication
import co.uan.pct.lib.core.api.NodePhase
import co.uan.pct.lib.core.api.PctDebugSnapshot
import co.uan.pct.lib.core.api.PctEvent
import co.uan.pct.lib.core.api.PctNode
import co.uan.pct.lib.core.api.TopologySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MeshUiState(
    val nodeId: String = "",
    val phase: NodePhase = NodePhase.ISLAND,
    val topology: TopologySnapshot? = null,
    val debug: PctDebugSnapshot = PctDebugSnapshot(),
    val logs: List<String> = emptyList(),
    val lastError: String? = null,
    val started: Boolean = false,
)

class MeshViewModel(
    private val app: PctMeshApplication,
) : ViewModel() {

    private fun node() = app.acquireNode()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _uiState = MutableStateFlow(MeshUiState(nodeId = node().nodeId))
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()

    init {
        bindFlows(node())
    }

    private fun bindFlows(pctNode: PctNode) {
        viewModelScope.launch {
            pctNode.phase.collect { phase ->
                _uiState.update { it.copy(phase = phase) }
            }
        }
        viewModelScope.launch {
            pctNode.topology.collect { topo ->
                _uiState.update { it.copy(topology = topo, nodeId = pctNode.nodeId) }
            }
        }
        viewModelScope.launch {
            pctNode.debug.collect { debug ->
                _uiState.update { it.copy(debug = debug, nodeId = pctNode.nodeId) }
            }
        }
        viewModelScope.launch {
            pctNode.events.collect { event ->
                when (event) {
                    is PctEvent.Log -> appendLog(event.message)
                    is PctEvent.Error -> {
                        appendLog("ERROR: ${event.message}")
                        _uiState.update { it.copy(lastError = event.message) }
                    }
                    is PctEvent.PhaseChanged -> appendLog("FASE → ${event.phase}")
                    is PctEvent.TopologyChanged -> appendLog(
                        "topo self=${event.snapshot.self.role} " +
                            "peers=${event.snapshot.knownPeers.size}",
                    )
                }
            }
        }
    }

    fun startMesh() {
        if (_uiState.value.started) return
        val pctNode = node()
        _uiState.update { it.copy(started = true, nodeId = pctNode.nodeId, lastError = null) }
        appendLog("UI: start() tras permisos")
        pctNode.start()
    }

    private fun appendLog(message: String) {
        val stamp = timeFmt.format(Date())
        _uiState.update { state ->
            state.copy(logs = (state.logs + "$stamp  $message").takeLast(200))
        }
    }

    class Factory(private val app: PctMeshApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MeshViewModel(app) as T
        }
    }
}
