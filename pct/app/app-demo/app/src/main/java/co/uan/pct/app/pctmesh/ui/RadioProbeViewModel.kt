package co.uan.pct.app.pctmesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import co.uan.pct.app.pctmesh.PctMeshApplication
import co.uan.pct.lib.core.MultiHopProtocol
import co.uan.pct.lib.core.types.NodeId
import co.uan.pct.lib.core.types.Role
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class NeighborRowUi(
    val id: String,
    val shortId: String,
    val name: String,
    val ipv4OnLink: String,
    val hops: Int,
)

data class RadioProbeUiState(
    val role: Role = Role.ISLAND,
    val parentMac: String? = null,
    val childMacs: List<String> = emptyList(),
    val neighborRows: List<NeighborRowUi> = emptyList(),
)

data class BroadcastMessageUi(
    val id: Long,
    val senderName: String,
    val body: String,
)

@OptIn(ExperimentalUuidApi::class)
class RadioProbeViewModel(
    private val protocol: MultiHopProtocol,
) : ViewModel() {

    private val displayNameCache = mutableMapOf<Uuid, String>()
    private val _broadcastMessages = MutableStateFlow<List<BroadcastMessageUi>>(emptyList())
    val broadcastMessages: StateFlow<List<BroadcastMessageUi>> = _broadcastMessages.asStateFlow()
    private val nextMessageId = AtomicLong(1)
    private var popJob: Job? = null

    val uiState: StateFlow<RadioProbeUiState> = combine(
        protocol.state,
        protocol.parentMac,
        protocol.connectedMacs,
        protocol.neighborTable,
    ) { role, parentMac, childMacs, neighborTable ->
        neighborTable.forEach { entry ->
            displayNameCache[entry.nodeId.identifier] = entry.nodeId.name
        }
        RadioProbeUiState(
            role = role,
            parentMac = parentMac,
            childMacs = childMacs,
            neighborRows = neighborTable.map { entry ->
                NeighborRowUi(
                    id = entry.nodeId.identifier.toString(),
                    shortId = entry.nodeId.identifier.toString().take(8),
                    name = entry.nodeId.name,
                    ipv4OnLink = entry.ipv4OnLink,
                    hops = entry.hops,
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RadioProbeUiState(),
    )

    init {
        runCatching {
            displayNameCache[protocol.nodeId.identifier] = protocol.nodeId.name
        }
    }

    fun start() {
        protocol.start()
        startPopLoop()
    }

    fun stop() {
        popJob?.cancel()
        popJob = null
        protocol.stop()
        _broadcastMessages.value = emptyList()
        displayNameCache.clear()
        runCatching {
            displayNameCache[protocol.nodeId.identifier] = protocol.nodeId.name
        }
    }

    fun sendChat(text: String, destId: String?) {
        val body = text.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            if (destId.isNullOrEmpty()) {
                runCatching { protocol.broadcast(body.toByteArray(Charsets.UTF_8)) }
                return@launch
            }
            val dest = protocol.neighborTable.value
                .firstOrNull { it.nodeId.identifier.toString() == destId }
                ?: return@launch
            runCatching { protocol.send(dest.nodeId, body.toByteArray(Charsets.UTF_8)) }
            _broadcastMessages.value = _broadcastMessages.value + BroadcastMessageUi(
                id = nextMessageId.getAndIncrement(),
                senderName = "${protocol.nodeId.name} → ${dest.nodeId.name}",
                body = body,
            )
        }
    }

    private fun startPopLoop() {
        popJob?.cancel()
        popJob = viewModelScope.launch {
            while (true) {
                val (origin, payload) = runCatching { protocol.pop() }.getOrNull() ?: break
                val body = runCatching { payload.decodeToString() }.getOrNull() ?: continue
                _broadcastMessages.value = _broadcastMessages.value + BroadcastMessageUi(
                    id = nextMessageId.getAndIncrement(),
                    senderName = resolveDisplayName(origin),
                    body = body,
                )
            }
        }
    }

    private fun resolveDisplayName(origin: NodeId): String {
        displayNameCache[origin.identifier]?.let { return it }
        if (runCatching { protocol.nodeId.identifier }.getOrNull() == origin.identifier) {
            return protocol.nodeId.name
        }
        displayNameCache[origin.identifier] = origin.name
        return origin.name
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
