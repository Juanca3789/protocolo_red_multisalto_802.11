package co.uan.pct.lib.core.lab

import co.uan.pct.lib.core.link.ArmPlan
import co.uan.pct.lib.core.link.SightKind
import co.uan.pct.lib.core.link.Volunteer
import co.uan.pct.lib.core.link.classifySight
import co.uan.pct.lib.core.link.planArm
import co.uan.pct.lib.core.link.volunteer
import co.uan.pct.lib.core.net.UserCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnrelatedTreesLabTest {

    @Test
    fun abcAndDeStayApartUntilInfiltrate() {
        val lab = twoForests()
        val abc = mapOf(LabIds.A to lab.node(LabIds.A), LabIds.B to lab.node(LabIds.B), LabIds.C to lab.node(LabIds.C))
        assertFalse(lab.node(LabIds.A).table.known(LabIds.D))
        assertFalse(lab.node(LabIds.C).table.known(LabIds.E))
        assertFalse(whoSearch(lab.node(LabIds.B), LabIds.D, abc))
        assertEquals(
            SightKind.OtherTree,
            classifySight(LabIds.D, lab.node(LabIds.B).table, false, true, someoneKnows = false),
        )
    }

    @Test
    fun freeArmOnFirstNodeInfiltratesAndMerges() = runBlocking {
        val lab = twoForests()
        val a = lab.node(LabIds.A)
        val b = lab.node(LabIds.B)
        val d = lab.node(LabIds.D)
        val abc = mapOf(LabIds.A to a, LabIds.B to b, LabIds.C to lab.node(LabIds.C))

        assertEquals(SightKind.Probe, classifySight(LabIds.D, b.table, false, false, false))
        assertFalse(whoSearch(b, LabIds.D, abc))
        assertEquals(
            SightKind.OtherTree,
            classifySight(LabIds.D, b.table, false, true, someoneKnows = false),
        )

        assertEquals(Volunteer.Sit, volunteer(hasRadioAccess = false, a.id, null))
        assertEquals(Volunteer.Offer, volunteer(hasRadioAccess = true, a.id, null))
        assertEquals(ArmPlan.Infiltrate, planArm(hasParent = false, childCount = a.table.childCount()))

        a.hear(d, asParent = false)
        d.hear(a, asParent = true)
        lab.gossipTabs()

        assertTrue("C debe ver a E tras el merge", lab.node(LabIds.C).table.known(LabIds.E))
        assertTrue(lab.node(LabIds.E).table.known(LabIds.C))
        assertEquals(LabIds.B, lab.node(LabIds.C).table.find(LabIds.E)?.next)

        val atE = listen(lab.node(LabIds.E))
        assertTrue(lab.node(LabIds.C).mesh.send(LabIds.E, "bosques".toByteArray()))
        assertArrayEquals("bosques".toByteArray(), withTimeout(1_000) { atE.await() }.payload)

        val atC = listen(lab.node(LabIds.C))
        assertTrue(lab.node(LabIds.E).mesh.send(LabIds.C, "vuelta".toByteArray()))
        val back = withTimeout(1_000) { atC.await() }
        assertArrayEquals("vuelta".toByteArray(), back.payload)
        assertTrue(UserCodec.sameNid(back.from, LabIds.E))
    }

    @Test
    fun whoDoesNotAssumeBadInitWhenNidUnknown() {
        val lab = twoForests()
        val abc = mapOf(
            LabIds.A to lab.node(LabIds.A),
            LabIds.B to lab.node(LabIds.B),
            LabIds.C to lab.node(LabIds.C),
        )
        assertFalse(whoSearch(lab.node(LabIds.C), LabIds.E, abc))
        assertTrue(lab.node(LabIds.C).table.known(LabIds.A))
    }

    private fun twoForests(): InMemoryLab {
        val lab = InMemoryLab()
        val a = lab.node(LabIds.A)
        val b = lab.node(LabIds.B)
        val c = lab.node(LabIds.C)
        val d = lab.node(LabIds.D)
        val e = lab.node(LabIds.E)
        chain(a, b)
        chain(b, c)
        lab.gossipTabs()
        chain(d, e)
        lab.gossipTabs()
        assertTrue(a.table.known(LabIds.C))
        assertTrue(d.table.known(LabIds.E))
        assertFalse(a.table.known(LabIds.D))
        return lab
    }

    private suspend fun CoroutineScope.listen(node: MemNode): Deferred<co.uan.pct.lib.core.net.UserMessage> {
        val pending = async { node.mesh.inbox.first() }
        delay(25)
        return pending
    }
}
