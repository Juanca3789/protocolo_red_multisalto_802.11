package co.uan.pct.lib.core.types

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class NodeId(
    val identifier: Uuid,
    val name: String
)
