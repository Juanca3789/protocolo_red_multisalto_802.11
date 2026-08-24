package co.uan.pct.app.pctmesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import co.uan.pct.app.pctmesh.PctMeshApplication
import co.uan.pct.lib.core.api.PctEvent
import co.uan.pct.lib.core.api.PctNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatLine(
    val fromNid: String,
    val text: String,
    val isOutgoing: Boolean,
    val timestamp: String,
)

data class MessengerUiState(
    val nodeId: String = "",
    val destinationNid: String = "",
    val messageText: String = "",
    val messages: List<ChatLine> = emptyList(),
    val neighborNids: List<String> = emptyList(),
)

class MessengerViewModel(
    private val app: PctMeshApplication,
) : ViewModel() {

    private fun node(): PctNode = app.acquireNode()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _uiState = MutableStateFlow(MessengerUiState(nodeId = node().nodeId))
    val uiState: StateFlow<MessengerUiState> = _uiState.asStateFlow()

    init {
        bindFlows(node())
    }

    private fun bindFlows(pctNode: PctNode) {
        viewModelScope.launch {
            pctNode.neighbors.collect { snap ->
                val nids = snap.neighbors.map { it.neighborNid }
                _uiState.update { it.copy(neighborNids = nids, nodeId = pctNode.nodeId) }
            }
        }
        viewModelScope.launch {
            pctNode.events.collect { event ->
                when (event) {
                    is PctEvent.UserMessage -> {
                        val line = ChatLine(
                            fromNid = event.fromNid,
                            text = event.text,
                            isOutgoing = event.fromNid == pctNode.nodeId,
                            timestamp = timeFmt.format(Date()),
                        )
                        _uiState.update { state ->
                            state.copy(messages = state.messages + line)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun onDestinationChange(value: String) {
        _uiState.update { it.copy(destinationNid = value.filter { c -> c.isLetterOrDigit() }.take(32)) }
    }

    fun onMessageChange(value: String) {
        _uiState.update { it.copy(messageText = value) }
    }

    fun sendMessage() {
        val state = _uiState.value
        val dest = state.destinationNid.trim()
        val text = state.messageText.trim()
        if (dest.length != 32 || text.isEmpty()) return

        val pctNode = node()
        val line = ChatLine(
            fromNid = pctNode.nodeId,
            text = text,
            isOutgoing = true,
            timestamp = timeFmt.format(Date()),
        )
        _uiState.update { it.copy(messages = it.messages + line, messageText = "") }
        pctNode.sendUser(dest, text.toByteArray(Charsets.UTF_8))
    }

    class Factory(private val app: PctMeshApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MessengerViewModel(app) as T
        }
    }
}
