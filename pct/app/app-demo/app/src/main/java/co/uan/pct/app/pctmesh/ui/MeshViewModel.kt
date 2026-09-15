package co.uan.pct.app.pctmesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import co.uan.pct.app.pctmesh.PctMeshApplication
import co.uan.pct.lib.core.physical.pctHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class RouteLine(
    val dest: String,
    val next: String,
    val hops: Int,
)

data class RadioLine(
    val nid: String,
    val ssid: String,
    val inTable: Boolean,
)

data class MeshUiState(
    val nodeId: String = "",
    val statusLine: String = "Esperando permisos",
    val parentId: String? = null,
    val depth: Int = 0,
    val neighborCount: Int = 0,
    val goOn: Boolean = false,
    val goSsid: String = "",
    val staOn: Boolean = false,
    val searching: Boolean = false,
    val routes: List<RouteLine> = emptyList(),
    val radio: List<RadioLine> = emptyList(),
    val foreign: List<String> = emptyList(),
    val action: String = "",
    val logs: List<String> = emptyList(),
    val lastError: String? = null,
    val started: Boolean = false,
)

@OptIn(ExperimentalUuidApi::class)
class MeshViewModel(
    private val app: PctMeshApplication,
) : ViewModel() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _uiState = MutableStateFlow(MeshUiState())
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()

    init {
        val node = app.acquireNode()
        combine(node.snapshot, node.link) { snap, link ->
            val nid = node.nodeId.ifBlank { snap.nodeId }
            val parent = link.parentId ?: snap.parentId
            val routes = link.routes
                .filter { it.dest != nid && it.hops > 0 }
                .map { RouteLine(it.dest, it.next, it.hops) }
            val inTable = routes.map { it.dest.take(8) }.toSet() +
                link.neighbors.map { it.take(8) }.toSet()
            val radio = snap.graph.peers.values.map { peer ->
                val peerNid = peer.service.nid.pctHex()
                RadioLine(
                    nid = peerNid,
                    ssid = peer.service.goSsid,
                    inTable = peerNid.take(8) in inTable,
                )
            }
            val neighbors = link.neighbors.size
            MeshUiState(
                nodeId = nid,
                statusLine = when {
                    neighbors > 0 -> "En red · $neighbors vecino(s) · ${link.depth} salto(s)"
                    snap.searching -> "Solo · buscando"
                    else -> "Solo · grupo propio listo"
                },
                parentId = parent,
                depth = link.depth,
                neighborCount = neighbors,
                goOn = snap.goReady,
                goSsid = snap.goSsid,
                staOn = snap.staConnected,
                searching = snap.searching,
                routes = routes,
                radio = radio,
                foreign = link.foreign,
                action = link.action.ifBlank { snap.action },
                logs = _uiState.value.logs,
                lastError = _uiState.value.lastError,
                started = _uiState.value.started,
            )
        }.onEach { derived ->
            _uiState.update { current ->
                val status = when {
                    !current.started -> "Esperando permisos"
                    else -> derived.statusLine
                }
                derived.copy(
                    statusLine = status,
                    logs = current.logs,
                    lastError = current.lastError,
                    started = current.started,
                )
            }
        }.launchIn(viewModelScope)

        node.logs.onEach { message ->
            val stamp = timeFmt.format(Date())
            _uiState.update { state ->
                state.copy(
                    logs = (state.logs + "$stamp  $message").takeLast(40),
                    lastError = if (
                        message.contains("error", ignoreCase = true) ||
                        message.contains("falló", ignoreCase = true)
                    ) {
                        message
                    } else {
                        state.lastError
                    },
                )
            }
        }.launchIn(viewModelScope)
    }

    fun startMesh() {
        if (_uiState.value.started) return
        val node = app.acquireNode()
        _uiState.update {
            it.copy(started = true, nodeId = node.nodeId, lastError = null, statusLine = "Arrancando…")
        }
        node.start()
    }

    class Factory(private val app: PctMeshApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MeshViewModel(app) as T
    }
}
