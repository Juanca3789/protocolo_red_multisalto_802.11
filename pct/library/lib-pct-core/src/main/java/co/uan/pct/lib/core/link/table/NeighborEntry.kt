package co.uan.pct.lib.core.link.table

import co.uan.pct.lib.core.types.NodeId

/** Fila de tabla link: vecino o destino conocido (índice = [NodeId.identifier]). */
data class NeighborEntry(
    val nodeId: NodeId,
    val ipv4OnLink: String,
    val hops: Int,
)
