package co.uan.pct.lib.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForestTest {

    @Test
    fun inTableIsOursNeverOtherTree() {
        val t = RouteTable("aa")
        t.installNeighbor("bb", "1.1.1.1", true)
        assertEquals(
            SightKind.Ours,
            classifySight("bb", t, probing = false, probed = false, someoneKnows = false),
        )
    }

    @Test
    fun unknownStartsAsProbe() {
        val t = RouteTable("aa")
        t.installNeighbor("bb", "1.1.1.1", true)
        assertEquals(
            SightKind.Probe,
            classifySight("cc", t, probing = false, probed = false, someoneKnows = false),
        )
    }

    @Test
    fun whileProbingWait() {
        val t = RouteTable("aa")
        t.installNeighbor("bb", "1.1.1.1", true)
        assertEquals(
            SightKind.Wait,
            classifySight("cc", t, probing = true, probed = false, someoneKnows = false),
        )
    }

    @Test
    fun afterProbeUnknownIsOtherTree() {
        val t = RouteTable("aa")
        t.installNeighbor("bb", "1.1.1.1", true)
        assertEquals(
            SightKind.OtherTree,
            classifySight("cc", t, probing = false, probed = true, someoneKnows = false),
        )
    }

    @Test
    fun afterProbeKnownByWhoIsOurs() {
        val t = RouteTable("aa")
        t.installNeighbor("bb", "1.1.1.1", true)
        assertEquals(
            SightKind.Ours,
            classifySight("cc", t, probing = false, probed = true, someoneKnows = true),
        )
    }

    @Test
    fun armPlans() {
        assertEquals(ArmPlan.Infiltrate, planArm(hasParent = false, childCount = 2))
        assertEquals(ArmPlan.DetachAndInfiltrate, planArm(hasParent = true, childCount = 0))
        assertEquals(ArmPlan.FreeArm, planArm(hasParent = true, childCount = 1))
    }

    @Test
    fun walkOrderNeighborsFirstParentLast() {
        val hops = mapOf("cc" to 1, "bb" to 1, "dd" to 2)
        val order = walkOrder(
            neighbors = listOf("bb", "cc"),
            parentId = "bb",
            hopOf = { hops[it] ?: 99 },
            except = emptySet(),
        )
        assertEquals(listOf("cc", "bb"), order)
    }

    @Test
    fun walkOrderSkipsExceptAndPrefersFewerHops() {
        val hops = mapOf("n1" to 1, "n2" to 3, "n3" to 1)
        val order = walkOrder(
            neighbors = listOf("n1", "n2", "n3"),
            parentId = null,
            hopOf = { hops[it] ?: 99 },
            except = setOf("n1"),
        )
        assertEquals(listOf("n3", "n2"), order)
    }

    @Test
    fun closerWaveKeepsOnCollision() {
        val local = WalkView("Who:cc", WalkKind.Who, origin = "aa", from = "x", hops = 1)
        val incoming = WalkView("Who:cc", WalkKind.Who, origin = "zz", from = "y", hops = 4)
        assertEquals(WalkHit.MergeKeep, onWalkArrive(local, incoming))
    }

    @Test
    fun fartherWaveYieldsOnCollision() {
        val local = WalkView("Who:cc", WalkKind.Who, origin = "zz", from = "x", hops = 4)
        val incoming = WalkView("Who:cc", WalkKind.Who, origin = "aa", from = "y", hops = 1)
        assertEquals(WalkHit.MergeYield, onWalkArrive(local, incoming))
    }

    @Test
    fun sameOriginIsLoopNotStorm() {
        val local = WalkView("Who:cc", WalkKind.Who, origin = "aa", from = "x", hops = 2)
        val incoming = WalkView("Who:cc", WalkKind.Who, origin = "aa", from = "y", hops = 3)
        assertEquals(WalkHit.Loop, onWalkArrive(local, incoming))
    }

    @Test
    fun equalHopsTieBreakByOriginNid() {
        val local = WalkView("See:cc", WalkKind.See, origin = "aa", from = "x", hops = 2)
        val incoming = WalkView("See:cc", WalkKind.See, origin = "bb", from = "y", hops = 2)
        assertEquals(WalkHit.MergeKeep, onWalkArrive(local, incoming))
        assertEquals(WalkHit.MergeYield, onWalkArrive(incoming, local))
    }

    @Test
    fun eventWalkMergesSecondOrigin() {
        val book = EventWalks()
        val hopOf = { _: String -> 1 }
        val first = book.begin(
            WalkKind.Who, "cccccccc", "aa", 0,
            listOf("bb", "cc"), null, hopOf,
        )
        assertEquals(true, first.second)
        val hit = book.arrive(
            WalkKind.Who, "cccccccc", "zz", "cc", 3,
            listOf("bb", "cc"), null, hopOf,
        )
        assertTrue(hit is WalkArrival.Keep)
        val yield = book.arrive(
            WalkKind.Who, "cccccccc", "aa", "bb", 0,
            listOf("bb", "cc"), null, hopOf,
        )
        assertTrue(yield is WalkArrival.Loop || yield is WalkArrival.Keep)
    }

    @Test
    fun volunteerWithoutRadioSits() {
        assertEquals(Volunteer.Sit, volunteer(false, "aa", null))
        assertEquals(Volunteer.Offer, volunteer(true, "aa", null))
        assertEquals(Volunteer.Yield, volunteer(true, "bb", "aa"))
        assertEquals(Volunteer.Offer, volunteer(true, "aa", "bb"))
    }
}
