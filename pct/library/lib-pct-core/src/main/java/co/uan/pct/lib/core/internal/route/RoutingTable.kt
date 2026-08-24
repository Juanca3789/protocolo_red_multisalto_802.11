package co.uan.pct.lib.core.internal.route

import co.uan.pct.lib.core.api.RouteEntry
import co.uan.pct.lib.core.api.RouteSnapshot
import co.uan.pct.lib.core.internal.link.NeighborRecord
import co.uan.pct.lib.core.internal.tcp.TopoRouteEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class RoutingEntry(
    val destinationUuid: String,
    val nextHopUuid: String,
    val nextHopLocalIp: String?,
    val hopCount: Int,
    val pathSeq: Int = 0,
    val status: Int = 0,
    val lastSeenMs: Long = System.currentTimeMillis(),
)

internal class RoutingTable(
    private val selfNid: () -> String,
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, RoutingEntry>()

    suspend fun rebuildDirect(neighbors: List<NeighborRecord>) = mutex.withLock {
        val self = selfNid()
        entries.clear()
        entries[self] = RoutingEntry(self, self, null, 0)
        for (n in neighbors) {
            entries[n.neighborNid] = RoutingEntry(
                destinationUuid = n.neighborNid,
                nextHopUuid = n.neighborNid,
                nextHopLocalIp = n.localIp,
                hopCount = 1,
                pathSeq = 1,
            )
        }
    }

    suspend fun merge(
        originNid: String,
        remote: List<TopoRouteEntry>,
        neighborHop: Int,
        originLocalIp: String?,
    ) = mutex.withLock {
        val self = selfNid()
        if (originNid == self) return@withLock
        for (e in remote) {
            if (e.destNid == self) continue
            val newHop = e.hopCount + neighborHop
            if (newHop > 7) continue
            val existing = entries[e.destNid]
            if (existing == null || e.pathSeq >= existing.pathSeq || newHop < existing.hopCount) {
                entries[e.destNid] = RoutingEntry(
                    destinationUuid = e.destNid,
                    nextHopUuid = originNid,
                    nextHopLocalIp = originLocalIp,
                    hopCount = newHop,
                    pathSeq = e.pathSeq,
                    status = e.status,
                )
            }
        }
    }

    suspend fun lookup(destNid: String): RoutingEntry? = mutex.withLock {
        entries[destNid]
    }

    suspend fun resolveNextHop(destNid: String): Pair<String, String?>? = mutex.withLock {
        val self = selfNid()
        if (destNid == self) return@withLock null
        val entry = entries[destNid] ?: return@withLock null
        if (entry.nextHopUuid == self) return@withLock null
        val ip = entry.nextHopLocalIp
            ?: entries[entry.nextHopUuid]?.nextHopLocalIp
        if (ip.isNullOrBlank()) return@withLock null
        entry.nextHopUuid to ip
    }

    suspend fun snapshot(): RouteSnapshot = mutex.withLock {
        RouteSnapshot(
            entries = entries.values
                .filter { it.destinationUuid != selfNid() }
                .map { e ->
                    RouteEntry(
                        destinationUuid = e.destinationUuid,
                        nextHopUuid = e.nextHopUuid,
                        nextHopLocalIp = e.nextHopLocalIp,
                        hopCount = e.hopCount,
                    )
                },
        )
    }

    suspend fun topoEntries(): List<TopoRouteEntry> = mutex.withLock {
        entries.values
            .filter { it.destinationUuid != selfNid() && it.hopCount > 0 }
            .take(8)
            .map { e ->
                TopoRouteEntry(
                    destNid = e.destinationUuid,
                    hopCount = e.hopCount,
                    status = e.status,
                    pathSeq = e.pathSeq,
                )
            }
    }
}
