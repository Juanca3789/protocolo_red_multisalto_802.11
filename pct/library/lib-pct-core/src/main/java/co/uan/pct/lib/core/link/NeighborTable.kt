package co.uan.pct.lib.core.link

import android.util.Log
import co.uan.pct.lib.core.types.NodeId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Vecinos a 1 salto; nunca incluye [localNodeId]. */
@OptIn(ExperimentalUuidApi::class)
class NeighborTable {
    private data class Entry(val nodeId: NodeId, val hops: Int)

    private val byDestUuid = linkedMapOf<Uuid, Entry>()
    private var localNodeId: NodeId? = null

    private val _entries = MutableStateFlow<List<Pair<NodeId, Int>>>(emptyList())
    val entries: StateFlow<List<Pair<NodeId, Int>>> = _entries.asStateFlow()

    fun bindLocalNode(nodeId: NodeId) {
        localNodeId = nodeId
        byDestUuid.keys.filter { it == nodeId.identifier }.forEach { byDestUuid.remove(it) }
        emitEntries()
    }

    fun addEntry(nodeId: NodeId, hops: Int) {
        require(hops >= 1) { "hops must be >= 1" }
        val self = localNodeId
        if (self != null && nodeId.identifier == self.identifier) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "addEntry ignored (self) name=${nodeId.name}")
            }
            return
        }
        byDestUuid[nodeId.identifier] = Entry(nodeId, hops)
        emitEntries()
    }

    fun clear() {
        byDestUuid.clear()
        _entries.value = emptyList()
    }

    private fun emitEntries() {
        _entries.value = byDestUuid.values
            .map { it.nodeId to it.hops }
            .sortedWith(compareBy({ it.second }, { it.first.name.lowercase() }))
    }

    private companion object {
        private const val LOG_TAG = "NeighborTable"
    }
}
