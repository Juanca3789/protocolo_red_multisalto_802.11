@file:OptIn(ExperimentalUuidApi::class)

package co.uan.pct.lib.core.physical

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val MAX_PARENT_CHILDREN = 8

private val peerOrder = compareBy<ServiceStructure>(
    { it.depth },
    { it.childCount },
    { it.nid.pctHex().take(8) },
)

/**
 * Orientación de **una** arista. Mismo depth (primer contacto) → menor `node_id` es padre.
 * Se compara el nid corto (8 hex) para coincidir con DNS-SD instance si el TXT no llega.
 */
@OptIn(ExperimentalUuidApi::class)
fun orientEdge(selfNid: Uuid, selfDepth: Int, peer: ServiceStructure): EdgeRole {
    val byDepth = peer.depth.compareTo(selfDepth)
    if (byDepth != 0) {
        return if (byDepth < 0) EdgeRole.CHILD else EdgeRole.PARENT
    }
    return if (peer.nid.pctHex().take(8) < selfNid.pctHex().take(8)) {
        EdgeRole.CHILD
    } else {
        EdgeRole.PARENT
    }
}

/**
 * Si aún no hay STA, intenta colgarse de un GO visible.
 * Empate de depth: solo el nid mayor inicia el STA (el menor espera).
 * Depth ajeno mayor (BRIDGE de otro árbol): se une para entrar a esa componente.
 */
@OptIn(ExperimentalUuidApi::class)
fun selectPeerToJoin(
    candidates: List<ServiceStructure>,
    selfNid: Uuid,
    selfDepth: Int,
    hasParent: Boolean,
): ServiceStructure? {
    if (hasParent) return null
    val selfShort = selfNid.pctHex().take(8)
    val usable = candidates
        .filter { it.nid.pctHex().take(8) != selfShort }
        .filter { it.goSsid.isNotBlank() && it.goPsk.isNotBlank() }
        .filter { it.childCount < MAX_PARENT_CHILDREN }
    val best = usable.minWithOrNull(peerOrder) ?: return null
    return when {
        best.depth < selfDepth -> best
        best.depth == selfDepth -> best.takeIf { orientEdge(selfNid, selfDepth, it) == EdgeRole.CHILD }
        else -> best
    }
}

@OptIn(ExperimentalUuidApi::class)
@Deprecated("Usar selectPeerToJoin", ReplaceWith("selectPeerToJoin(candidates, selfNid, 0, false)"))
fun selectBestParent(candidates: List<ServiceStructure>, selfNid: Uuid): ServiceStructure? =
    selectPeerToJoin(candidates, selfNid, selfDepth = 0, hasParent = false)
