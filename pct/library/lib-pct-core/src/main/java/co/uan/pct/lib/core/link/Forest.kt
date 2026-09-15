package co.uan.pct.lib.core.link

enum class SightKind { Ours, Probe, Wait, OtherTree }

enum class ArmPlan { Infiltrate, DetachAndInfiltrate, FreeArm }

enum class Volunteer { Sit, Offer, Yield }

enum class WalkKind { Who, See, Going }

enum class WalkHit { Fresh, Loop, MergeKeep, MergeYield }

data class WalkView(
    val eid: String,
    val kind: WalkKind,
    val origin: String,
    val from: String?,
    val hops: Int,
)

data class WalkState(
    val eid: String,
    val kind: WalkKind,
    val target: String,
    var origin: String,
    var from: String?,
    var hops: Int,
    var known: Boolean = false,
    var claimedBy: String? = null,
    val remaining: ArrayDeque<String> = ArrayDeque(),
    var waiting: String? = null,
) {
    fun toView(): WalkView = WalkView(eid, kind, origin, from, hops)

    val isOrigin: Boolean get() = from == null
}

sealed class WalkArrival {
    data class Fresh(val state: WalkState) : WalkArrival()
    data object Loop : WalkArrival()
    data class Keep(val state: WalkState) : WalkArrival()
    data class Yield(val state: WalkState, val oldFrom: String?) : WalkArrival()
}

fun classifySight(
    nid: String,
    table: RouteTable,
    probing: Boolean,
    probed: Boolean,
    someoneKnows: Boolean,
    claimed: Boolean = false,
): SightKind {
    if (table.known(nid)) return SightKind.Ours
    if (probing || claimed) return SightKind.Wait
    if (!probed) return SightKind.Probe
    if (someoneKnows) return SightKind.Ours
    return SightKind.OtherTree
}

fun planArm(hasParent: Boolean, childCount: Int): ArmPlan = when {
    !hasParent -> ArmPlan.Infiltrate
    childCount == 0 -> ArmPlan.DetachAndInfiltrate
    else -> ArmPlan.FreeArm
}

fun eventId(kind: WalkKind, targetNid: String): String =
    "${kind.name}:${targetNid.take(8)}"

fun walkOrder(
    neighbors: List<String>,
    parentId: String?,
    hopOf: (String) -> Int,
    except: Set<String>,
): List<String> = neighbors
    .filter { it !in except }
    .sortedWith(
        compareBy<String> { hop ->
            val hops = hopOf(hop)
            if (hops <= 0) 99 else hops
        }
            .thenBy { if (it == parentId) 1 else 0 }
            .thenBy { it },
    )

fun onWalkArrive(existing: WalkView?, incoming: WalkView): WalkHit {
    if (existing == null) return WalkHit.Fresh
    if (existing.origin == incoming.origin) return WalkHit.Loop
    val keepExisting = when {
        existing.hops < incoming.hops -> true
        incoming.hops < existing.hops -> false
        else -> existing.origin <= incoming.origin
    }
    return if (keepExisting) WalkHit.MergeKeep else WalkHit.MergeYield
}

fun claimWinner(a: String, b: String): String = if (a <= b) a else b

fun volunteer(
    hasRadioAccess: Boolean,
    selfNid: String,
    claimedBy: String?,
): Volunteer {
    if (!hasRadioAccess) return Volunteer.Sit
    val claim = claimedBy ?: return Volunteer.Offer
    if (claim == selfNid) return Volunteer.Offer
    return if (selfNid < claim) Volunteer.Offer else Volunteer.Yield
}

class EventWalks {
    private val byEid = linkedMapOf<String, WalkState>()

    fun get(eid: String): WalkState? = byEid[eid]

    fun claimed(targetNid: String): Boolean =
        get(eventId(WalkKind.See, targetNid)) != null ||
            get(eventId(WalkKind.Going, targetNid)) != null ||
            get(eventId(WalkKind.Who, targetNid)) != null

    fun begin(
        kind: WalkKind,
        target: String,
        origin: String,
        hops: Int,
        neighbors: List<String>,
        parentId: String?,
        hopOf: (String) -> Int,
    ): Pair<WalkState, Boolean> {
        val eid = eventId(kind, target)
        byEid[eid]?.let { return it to false }
        val ordered = walkOrder(neighbors, parentId, hopOf, except = emptySet())
        val walk = WalkState(
            eid = eid,
            kind = kind,
            target = target,
            origin = origin,
            from = null,
            hops = hops,
            remaining = ArrayDeque(ordered),
        )
        byEid[eid] = walk
        return walk to true
    }

    fun arrive(
        kind: WalkKind,
        target: String,
        origin: String,
        from: String,
        hops: Int,
        neighbors: List<String>,
        parentId: String?,
        hopOf: (String) -> Int,
    ): WalkArrival {
        val eid = eventId(kind, target)
        val incoming = WalkView(eid, kind, origin, from, hops)
        val existing = byEid[eid]
        return when (onWalkArrive(existing?.toView(), incoming)) {
            WalkHit.Fresh -> {
                val walk = WalkState(
                    eid = eid,
                    kind = kind,
                    target = target,
                    origin = origin,
                    from = from,
                    hops = hops,
                    remaining = ArrayDeque(
                        walkOrder(neighbors, parentId, hopOf, except = setOf(from)),
                    ),
                )
                byEid[eid] = walk
                WalkArrival.Fresh(walk)
            }
            WalkHit.Loop -> WalkArrival.Loop
            WalkHit.MergeKeep -> WalkArrival.Keep(existing!!)
            WalkHit.MergeYield -> {
                val oldFrom = existing!!.from
                existing.origin = origin
                existing.from = from
                existing.hops = hops
                existing.waiting = null
                existing.remaining.clear()
                existing.remaining.addAll(
                    walkOrder(neighbors, parentId, hopOf, except = setOf(from)),
                )
                WalkArrival.Yield(existing, oldFrom)
            }
        }
    }

    fun takeNext(eid: String): String? {
        val walk = byEid[eid] ?: return null
        val next = walk.remaining.removeFirstOrNull()
        walk.waiting = next
        return next
    }

    fun onBranchDone(eid: String, fromNid: String, known: Boolean): WalkState? {
        val walk = byEid[eid] ?: return null
        if (walk.waiting == fromNid) walk.waiting = null
        if (known) {
            walk.known = true
            walk.remaining.clear()
            walk.waiting = null
        }
        return walk
    }

    fun markClaimed(eid: String, by: String) {
        val walk = byEid[eid] ?: return
        walk.claimedBy = walk.claimedBy?.let { claimWinner(it, by) } ?: by
    }

    fun remove(eid: String): WalkState? = byEid.remove(eid)
}
