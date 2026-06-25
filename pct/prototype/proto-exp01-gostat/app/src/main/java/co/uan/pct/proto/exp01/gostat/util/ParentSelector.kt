package co.uan.pct.proto.exp01.gostat.util

import co.uan.pct.proto.exp01.gostat.data.p2p.model.PctCtrlCandidate

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

    fun best(candidates: List<PctCtrlCandidate>, localNodeId: String?): PctCtrlCandidate? =
        rank(candidates, localNodeId).firstOrNull()

    fun isSelf(candidate: PctCtrlCandidate, localNodeId: String?): Boolean {
        if (localNodeId.isNullOrBlank()) return false
        val localShort = localNodeId.take(8)
        val remoteShort = candidate.record.nid.take(8)
        return localShort.equals(remoteShort, ignoreCase = true)
    }

    private fun roleRank(role: String): Int = when (role.uppercase()) {
        "ROOT" -> 0
        "BRIDGE" -> 1
        "LEAF" -> 2
        else -> 3
    }
}
