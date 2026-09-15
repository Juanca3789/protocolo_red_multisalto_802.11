package co.uan.pct.lib.core.physical

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class NodeConfig(
    val nodeId: Uuid,
    val nodeName: String,
)

/** Etiqueta de anuncio. ROOT = primero / sin padre; no da poderes extra. */
enum class Role {
    ROOT,
    BRIDGE,
    LEAF,
    ISLAND,
}

enum class EdgeRole {
    PARENT,
    CHILD,
}

@OptIn(ExperimentalUuidApi::class)
sealed interface NodeState {
    data object Init : NodeState
    data object Starting : NodeState
    data class Running(
        val role: Role,
        val parentId: Uuid?,
        val depth: Int,
    ) : NodeState
    data object Closed : NodeState
}

@OptIn(ExperimentalUuidApi::class)
data class ServiceStructure(
    val nid: Uuid,
    val role: Role,
    val depth: Int,
    val ctrlPort: Int,
    val goSsid: String,
    val goPsk: String,
    val childCount: Int,
    val p2pDeviceAddress: String = "",
    val v: Int = 1,
)

@OptIn(ExperimentalUuidApi::class)
data class SeenPeer(
    val service: ServiceStructure,
    val lastSeenMs: Long,
    val staToPeer: Boolean,
    val edgeVsSelf: EdgeRole,
)

@OptIn(ExperimentalUuidApi::class)
data class RelativeGraph(
    val peers: Map<String, SeenPeer> = emptyMap(),
)

@OptIn(ExperimentalUuidApi::class)
data class PhysicalSnapshot(
    val nodeId: String = "",
    val role: Role = Role.ROOT,
    val depth: Int = 0,
    val parentId: String? = null,
    val goSsid: String = "",
    val goReady: Boolean = false,
    val staSsid: String? = null,
    val staConnected: Boolean = false,
    val searching: Boolean = false,
    val childCount: Int = 0,
    val action: String = "",
    val graph: RelativeGraph = RelativeGraph(),
)
