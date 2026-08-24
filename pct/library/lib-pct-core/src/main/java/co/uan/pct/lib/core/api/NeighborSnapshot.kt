package co.uan.pct.lib.core.api

enum class NeighborIface {
    UPSTREAM,
    DOWNSTREAM,
}

enum class DataChannelState {
    CLOSED,
    OPEN,
    RECONNECTING,
}

data class NeighborEntry(
    val neighborNid: String,
    val iface: NeighborIface,
    val localIp: String,
    val role: String,
    val hop: Int,
    val dataChannelState: DataChannelState,
)

data class NeighborSnapshot(
    val neighbors: List<NeighborEntry> = emptyList(),
)
