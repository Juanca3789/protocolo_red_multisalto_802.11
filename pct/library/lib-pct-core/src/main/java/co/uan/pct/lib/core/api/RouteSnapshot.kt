package co.uan.pct.lib.core.api

data class RouteEntry(
    val destinationUuid: String,
    val nextHopUuid: String,
    val nextHopLocalIp: String?,
    val hopCount: Int,
)

data class RouteSnapshot(
    val entries: List<RouteEntry> = emptyList(),
)
