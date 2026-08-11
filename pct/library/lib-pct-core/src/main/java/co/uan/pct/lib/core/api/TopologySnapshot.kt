package co.uan.pct.lib.core.api

data class TopologyNode(
    val nodeId: String,
    val role: String,
    val hop: Int,
    val goSsid: String? = null,
)

data class TopologySnapshot(
    val self: TopologyNode,
    val parent: TopologyNode? = null,
    val children: List<TopologyNode> = emptyList(),
    val knownPeers: List<TopologyNode> = emptyList(),
)
