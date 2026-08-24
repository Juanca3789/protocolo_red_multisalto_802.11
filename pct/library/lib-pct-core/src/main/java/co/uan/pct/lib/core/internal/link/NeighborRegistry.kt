package co.uan.pct.lib.core.internal.link

import co.uan.pct.lib.core.api.DataChannelState
import co.uan.pct.lib.core.api.NeighborEntry
import co.uan.pct.lib.core.api.NeighborIface
import co.uan.pct.lib.core.api.NeighborSnapshot
import co.uan.pct.lib.core.internal.tcp.PctControlSocket
import co.uan.pct.lib.core.internal.tcp.PctDataSocket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class NeighborRecord(
    val neighborNid: String,
    val iface: NeighborIface,
    var localIp: String,
    val ctrlPort: Int,
    val dataPort: Int,
    var ctrlSocket: PctControlSocket? = null,
    var dataSocket: PctDataSocket? = null,
    var dataChannelState: DataChannelState = DataChannelState.CLOSED,
    var role: String = "LEAF",
    var hop: Int = 0,
    var lastCtrlMs: Long = 0L,
    var lastDataMs: Long = 0L,
)

internal class NeighborRegistry {
    private val mutex = Mutex()
    private val byNid = LinkedHashMap<String, NeighborRecord>()
    private val byIp = LinkedHashMap<String, String>()

    suspend fun upsert(record: NeighborRecord): NeighborRecord = mutex.withLock {
        byNid[record.neighborNid] = record
        byIp[record.localIp] = record.neighborNid
        record
    }

    suspend fun get(nid: String): NeighborRecord? = mutex.withLock { byNid[nid] }

    suspend fun getByIp(ip: String): NeighborRecord? = mutex.withLock {
        byIp[ip]?.let { byNid[it] }
    }

    suspend fun upstream(): NeighborRecord? = mutex.withLock {
        byNid.values.firstOrNull { it.iface == NeighborIface.UPSTREAM }
    }

    suspend fun downstream(): List<NeighborRecord> = mutex.withLock {
        byNid.values.filter { it.iface == NeighborIface.DOWNSTREAM }
    }

    suspend fun all(): List<NeighborRecord> = mutex.withLock { byNid.values.toList() }

    suspend fun update(nid: String, block: (NeighborRecord) -> Unit): NeighborRecord? = mutex.withLock {
        val r = byNid[nid] ?: return@withLock null
        block(r)
        if (r.localIp.isNotBlank()) {
            byIp[r.localIp] = nid
        }
        r
    }

    suspend fun remove(nid: String) = mutex.withLock {
        val r = byNid.remove(nid) ?: return@withLock
        byIp.entries.removeIf { it.value == nid }
        runCatching { r.ctrlSocket?.close() }
        runCatching { r.dataSocket?.close() }
    }

    suspend fun snapshot(): NeighborSnapshot = mutex.withLock {
        NeighborSnapshot(
            neighbors = byNid.values.map { r ->
                NeighborEntry(
                    neighborNid = r.neighborNid,
                    iface = r.iface,
                    localIp = r.localIp,
                    role = r.role,
                    hop = r.hop,
                    dataChannelState = r.dataChannelState,
                )
            },
        )
    }

    suspend fun linkStats(): Triple<Int, Int, Int> = mutex.withLock {
        var ctrl = 0
        var dataOpen = 0
        var dataRecon = 0
        for (r in byNid.values) {
            if (r.ctrlSocket != null && !r.ctrlSocket!!.isClosed) ctrl++
            when (r.dataChannelState) {
                DataChannelState.OPEN -> dataOpen++
                DataChannelState.RECONNECTING -> dataRecon++
                DataChannelState.CLOSED -> Unit
            }
        }
        Triple(ctrl, dataOpen, dataRecon)
    }
}
