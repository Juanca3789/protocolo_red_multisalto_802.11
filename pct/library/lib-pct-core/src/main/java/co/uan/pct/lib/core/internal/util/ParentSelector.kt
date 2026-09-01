package co.uan.pct.lib.core.internal.util

import co.uan.pct.lib.core.internal.p2p.model.PctCtrlCandidate

object ParentSelector {

    fun rank(candidates: List<PctCtrlCandidate>, localNodeId: String?): List<PctCtrlCandidate> {
        return candidates
            .filterNot { isSelf(it, localNodeId) }
            .filter { it.record.goSsid.isNotBlank() && it.record.goPsk.isNotBlank() }
            .sortedWith(
                compareBy<PctCtrlCandidate> { it.record.hop }
                    .thenBy { roleRank(it.record.role) }
                    .thenBy { it.discoveredAtMs },
            )
    }

    fun isSelf(candidate: PctCtrlCandidate, localNodeId: String?): Boolean {
        if (localNodeId.isNullOrBlank()) return false
        return PctNid.matches(localNodeId, candidate.record.nid)
    }

    fun best(candidates: List<PctCtrlCandidate>, localNodeId: String?): PctCtrlCandidate? {
        val ranked = rank(candidates, localNodeId)
        return ranked.firstOrNull { PctNid.isFull(it.record.nid) } ?: ranked.firstOrNull()
    }

    private fun roleRank(role: String): Int = when (role.uppercase()) {
        "ROOT" -> 0
        "BRIDGE" -> 1
        "LEAF" -> 2
        else -> 3
    }
}
