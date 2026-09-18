package co.uan.pct.lib.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTableTest {

    @Test
    fun orphanUntilNeighbor() {
        val t = RouteTable("aa")
        assertTrue(t.isOrphan())
        t.installNeighbor("bb", "192.168.49.1", asParent = true)
        assertFalse(t.isOrphan())
        assertEquals("bb", t.parentId)
        assertTrue(t.known("bb"))
    }

    @Test
    fun mergeDoesNotLoopToSelf() {
        val t = RouteTable("aa")
        t.installNeighbor("bb", "192.168.49.1", asParent = true)
        t.mergeFrom("bb", listOf("aa" to 1, "cc" to 1))
        assertEquals(2, t.find("cc")?.hops)
        assertEquals("bb", t.find("cc")?.next)
        assertEquals(0, t.find("aa")?.hops)
    }

    @Test
    fun dropNeighborRemovesDependentRoutes() {
        val t = RouteTable("aa")
        t.installNeighbor("bb", "1.1.1.1", asParent = true)
        t.mergeFrom("bb", listOf("cc" to 1))
        t.dropNeighbor("bb")
        assertFalse(t.known("bb"))
        assertFalse(t.known("cc"))
        assertTrue(t.isOrphan())
    }

    @Test
    fun knownByPrefix() {
        val t = RouteTable("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        t.installNeighbor("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "9.9.9.9", false)
        assertTrue(t.known("bbbbbbbb"))
    }

    @Test
    fun mergeWithdrawsStaleViaThatNeighbor() {
        val t = RouteTable("aa")
        t.installNeighbor("bb", "1.1.1.1", true)
        assertTrue(t.mergeFrom("bb", listOf("cc" to 1, "dd" to 2)))
        assertTrue(t.known("cc"))
        assertTrue(t.mergeFrom("bb", listOf("cc" to 1)))
        assertFalse(t.known("dd"))
        assertTrue(t.known("cc"))
    }
}
