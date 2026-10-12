package co.uan.pct.lib.core.link.table

import co.uan.pct.lib.core.types.NodeId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(ExperimentalUuidApi::class)
class NeighborTable {
    private val lock = Any()
    private val byUuid = linkedMapOf<Uuid, NeighborEntry>()

    private val _entries = MutableStateFlow<List<NeighborEntry>>(emptyList())
    val entries: StateFlow<List<NeighborEntry>> = _entries.asStateFlow()

    fun get(uuid: Uuid): NeighborEntry? = synchronized(lock) { byUuid[uuid] }

    fun addEntry(nodeId: NodeId, ipv4OnLink: String, hops: Int) {
        require(hops >= 1) { "hops must be >= 1" }
        synchronized(lock) {
            byUuid[nodeId.identifier] = NeighborEntry(nodeId, ipv4OnLink, hops)
            emitEntriesLocked()
        }
    }

    /**
     * Destino remoto (REACH): instala o mejora. `ipv4OnLink` es el next-jump (vecino de ingreso).
     * `true` si la tabla cambió (nuevo o hops estrictamente menores).
     */
    fun installIfBetter(nodeId: NodeId, nextJumpIpv4: String, hops: Int): Boolean {
        require(hops >= 1) { "hops must be >= 1" }
        synchronized(lock) {
            val old = byUuid[nodeId.identifier]
            if (old != null && old.hops <= hops) return false
            byUuid[nodeId.identifier] = NeighborEntry(nodeId, nextJumpIpv4, hops)
            emitEntriesLocked()
            return true
        }
    }

    fun removeEntryByUuid(uuid: Uuid) {
        synchronized(lock) {
            if (byUuid.remove(uuid) != null) {
                emitEntriesLocked()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            byUuid.clear()
            _entries.value = emptyList()
        }
    }

    private fun emitEntriesLocked() {
        _entries.value = byUuid.values
            .sortedWith(compareBy({ it.hops }, { it.nodeId.name.lowercase() }))
    }
}
