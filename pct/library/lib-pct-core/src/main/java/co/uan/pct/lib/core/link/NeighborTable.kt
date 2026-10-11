package co.uan.pct.lib.core.link

import co.uan.pct.lib.core.types.NodeId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Destinos alcanzables → saltos; estado expuesto en [entries]. */
@OptIn(ExperimentalUuidApi::class)
class NeighborTable {
    private data class Entry(val nodeId: NodeId, val hops: Int)

    private val byDestUuid = linkedMapOf<Uuid, Entry>()

    private val _entries = MutableStateFlow<List<Pair<NodeId, Int>>>(emptyList())
    val entries: StateFlow<List<Pair<NodeId, Int>>> = _entries.asStateFlow()

    fun addEntry(nodeId: NodeId, hops: Int) {
        require(hops >= 1) { "hops must be >= 1" }
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
}
