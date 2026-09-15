package co.uan.pct.app.pctmesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import co.uan.pct.app.pctmesh.PctMeshApplication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class ChatLine(
    val fromNid: String,
    val text: String,
    val isOutgoing: Boolean,
    val timestamp: String,
)

data class DestOption(
    val label: String,
    val nid: String,
)

data class MessengerUiState(
    val nodeId: String = "",
    val destinationNid: String = "",
    val messageText: String = "",
    val messages: List<ChatLine> = emptyList(),
    val destinations: List<DestOption> = emptyList(),
    val hint: String = "La tabla aún no tiene destinos",
)

class MessengerViewModel(
    private val app: PctMeshApplication,
) : ViewModel() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _uiState = MutableStateFlow(MessengerUiState())
    val uiState: StateFlow<MessengerUiState> = _uiState.asStateFlow()

    init {
        val node = app.acquireNode()
        _uiState.update { it.copy(nodeId = node.nodeId) }

        node.link.onEach { link ->
            val self = node.nodeId
            val dests = link.routes
                .filter { it.dest != self && it.hops > 0 }
                .distinctBy { it.dest.take(8) }
                .map { row ->
                    DestOption(
                        label = "${row.dest.take(8)} · ${row.hops} salto(s) vía ${row.next.take(8)}",
                        nid = row.dest,
                    )
                }
            _uiState.update { state ->
                val chosen = when {
                    state.destinationNid.length == 32 -> state.destinationNid
                    dests.any { it.nid == state.destinationNid } -> state.destinationNid
                    state.destinationNid.isEmpty() && dests.isNotEmpty() -> dests.first().nid
                    else -> state.destinationNid
                }
                state.copy(
                    nodeId = self,
                    destinations = dests,
                    destinationNid = chosen,
                    hint = if (dests.isEmpty()) {
                        "Nadie en la tabla — espera a que se una el árbol"
                    } else {
                        "El mensaje va al identificador, no a una IP"
                    },
                )
            }
        }.launchIn(viewModelScope)

        node.inbox.onEach { msg ->
            _uiState.update {
                it.copy(
                    messages = it.messages + ChatLine(
                        fromNid = msg.from,
                        text = msg.payload.toString(Charsets.UTF_8),
                        isOutgoing = false,
                        timestamp = timeFmt.format(Date()),
                    ),
                )
            }
        }.launchIn(viewModelScope)
    }

    fun onDestinationChange(value: String) {
        _uiState.update {
            it.copy(destinationNid = value.filter { c -> c.isLetterOrDigit() }.take(32).lowercase())
        }
    }

    fun selectDestination(nid: String) {
        _uiState.update { it.copy(destinationNid = nid.lowercase()) }
    }

    fun onMessageChange(value: String) {
        _uiState.update { it.copy(messageText = value) }
    }

    fun sendMessage() {
        val state = _uiState.value
        val dest = state.destinationNid.trim()
        val text = state.messageText.trim()
        if (dest.length != 32 || text.isEmpty()) return
        val node = app.acquireNode()
        _uiState.update {
            it.copy(
                messages = it.messages + ChatLine(
                    fromNid = node.nodeId,
                    text = text,
                    isOutgoing = true,
                    timestamp = timeFmt.format(Date()),
                ),
                messageText = "",
            )
        }
        node.sendUser(dest, text.toByteArray(Charsets.UTF_8))
    }

    class Factory(private val app: PctMeshApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MessengerViewModel(app) as T
    }
}
