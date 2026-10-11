package co.uan.pct.lib.core.link.table

import co.uan.pct.lib.core.types.NodeId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(ExperimentalUuidApi::class)
class NeighborTable {
    private val byUuid = linkedMapOf<Uuid, NeighborEntry>()

    private val _entries = MutableStateFlow<List<NeighborEntry>>(emptyList())
    val entries: StateFlow<List<NeighborEntry>> = _entries.asStateFlow()

    fun addEntry(nodeId: NodeId, ipv4OnLink: String, hops: Int) {
        require(hops >= 1) { "hops must be >= 1" }
        byUuid[nodeId.identifier] = NeighborEntry(nodeId, ipv4OnLink, hops)
        emitEntries()
    }

    fun removeEntryByUuid(uuid: Uuid) {
        if (byUuid.remove(uuid) != null) {
            emitEntries()
        }
    }

    fun clear() {
        byUuid.clear()
        _entries.value = emptyList()
    }

    private fun emitEntries() {
        _entries.value = byUuid.values
            .sortedWith(compareBy({ it.hops }, { it.nodeId.name.lowercase() }))
    }
}
