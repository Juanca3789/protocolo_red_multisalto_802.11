@file:OptIn(ExperimentalUuidApi::class)

package co.uan.pct.lib.core.physical

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Orientación relativa de un vecino visto por radio, solo para la vista de depuración.
 * La decisión real de padre/hijo la hace L2 por el sentido del socket (quien conecta es hijo).
 *
 * Mismo depth (primer contacto) → menor `node_id` es padre. Se compara el nid corto (8 hex)
 * para coincidir con el nombre de instancia DNS-SD cuando el TXT completo no llega.
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
