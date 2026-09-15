package co.uan.pct.lib.core.link

data class LinkSnapshot(
    val tree: String = "",
    val parentId: String? = null,
    val depth: Int = 0,
    val neighbors: List<String> = emptyList(),
    val routes: List<RouteView> = emptyList(),
    val foreign: List<String> = emptyList(),
    val dataOpen: Int = 0,
    val action: String = "",
)
