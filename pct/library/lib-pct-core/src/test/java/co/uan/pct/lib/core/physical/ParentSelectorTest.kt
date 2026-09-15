package co.uan.pct.lib.core.physical

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalUuidApi::class)
class ParentSelectorTest {

    private val low = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val high = Uuid.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    private fun candidate(
        nid: Uuid,
        depth: Int,
        children: Int = 0,
    ) = ServiceStructure(
        nid = nid,
        role = if (depth == 0) Role.ROOT else Role.BRIDGE,
        depth = depth,
        ctrlPort = 8765,
        goSsid = "DIRECT-xx",
        goPsk = "psk123456789012",
        childCount = children,
    )

    @Test
    fun sameDepth_higherNidJoinsLower() {
        val peer = candidate(low, depth = 0)
        assertEquals(EdgeRole.CHILD, orientEdge(high, 0, peer))
        assertEquals(peer.nid, selectPeerToJoin(listOf(peer), high, 0, hasParent = false)?.nid)
    }

    @Test
    fun sameDepth_lowerNidDoesNotJoin() {
        val peer = candidate(high, depth = 0)
        assertEquals(EdgeRole.PARENT, orientEdge(low, 0, peer))
        assertNull(selectPeerToJoin(listOf(peer), low, 0, hasParent = false))
    }

    @Test
    fun hasParent_doesNotJoin() {
        assertNull(
            selectPeerToJoin(listOf(candidate(low, 0)), high, 1, hasParent = true),
        )
    }

    @Test
    fun deeperBridge_lonelyRootJoinsToEnterTree() {
        val bridge = candidate(high, depth = 1)
        assertNotNull(selectPeerToJoin(listOf(bridge), low, 0, hasParent = false))
    }

    @Test
    fun excludesSelfAndFullNodes() {
        assertNull(selectPeerToJoin(listOf(candidate(high, 0)), high, 0, false))
        assertNull(
            selectPeerToJoin(listOf(candidate(low, 0, children = 8)), high, 0, false),
        )
    }
}
