package co.uan.pct.lib.core.link

data class RouteView(
    val dest: String,
    val next: String,
    val hops: Int,
)

data class RouteRow(
    val dest: String,
    val next: String,
    val hops: Int,
    val nextIp: String? = null,
)

private const val HOP_LIMIT = 7

class RouteTable(val self: String) {
    private val rows = linkedMapOf<String, RouteRow>()
    var parentId: String? = null
        private set

    init {
        rows[self] = RouteRow(self, self, 0)
    }

    fun snapshot(): List<RouteView> =
        rows.values.map { RouteView(it.dest, it.next, it.hops) }

    fun known(nid: String): Boolean = find(nid) != null

    fun find(nid: String): RouteRow? {
        rows[nid]?.let { return it }
        val short = nid.take(8)
        return rows.values.firstOrNull { it.dest.take(8) == short }
    }

    fun isOrphan(): Boolean = parentId == null && neighbors().isEmpty()

    fun neighbors(): List<String> =
        rows.values.filter { it.hops == 1 }.map { it.dest }

    fun childIds(): List<String> =
        neighbors().filter { it != parentId }

    fun childCount(): Int = childIds().size

    fun advertise(): List<Pair<String, Int>> =
        rows.values.filter { it.dest != self }.map { it.dest to it.hops }

    fun installNeighbor(nid: String, ip: String, asParent: Boolean) {
        rows[nid] = RouteRow(nid, nid, 1, ip)
        if (asParent) parentId = nid
    }

    fun hopOf(nid: String): Int = find(nid)?.hops ?: 99

    fun mergeFrom(fromNeighbor: String, advertised: List<Pair<String, Int>>): Boolean {
        var changed = false
        for ((dest, hops) in advertised) {
            if (dest == self || dest.take(8) == self.take(8)) continue
            if (hops >= HOP_LIMIT) continue
            val candidateHops = hops + 1
            if (candidateHops > HOP_LIMIT) continue
            val existing = find(dest)
            if (existing == null || candidateHops < existing.hops) {
                val ip = rows[fromNeighbor]?.nextIp
                rows[dest] = RouteRow(dest, fromNeighbor, candidateHops, ip)
                changed = true
            }
        }
        val keep = advertised.map { it.first.take(8) }.toSet() + fromNeighbor.take(8)
        val stale = rows.filter { (dest, row) ->
            dest != self && row.next == fromNeighbor && dest.take(8) !in keep
        }.keys
        stale.forEach {
            rows.remove(it)
            changed = true
        }
        return changed
    }

    fun dropNeighbor(nid: String) {
        if (parentId == nid) parentId = null
        val gone = rows.filter { it.value.next == nid || it.key == nid }.keys
        gone.forEach { rows.remove(it) }
        rows[self] = RouteRow(self, self, 0)
    }

    fun setParent(nid: String?) {
        parentId = nid
    }
}
