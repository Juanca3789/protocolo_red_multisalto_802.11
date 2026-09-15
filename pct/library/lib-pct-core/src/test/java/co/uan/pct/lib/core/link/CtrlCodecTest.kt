package co.uan.pct.lib.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CtrlCodecTest {

    @Test
    fun hiRoundTrip() {
        val msg = CtrlMsg.Hi("aa", 1, "root", listOf("bb" to 1, "cc" to 2))
        val decoded = CtrlCodec.decode(CtrlCodec.encode(msg)) as CtrlMsg.Hi
        assertEquals("aa", decoded.nid)
        assertEquals(1, decoded.depth)
        assertEquals("root", decoded.tree)
        assertEquals(listOf("bb" to 1, "cc" to 2), decoded.routes)
    }

    @Test
    fun seeRoundTripKeepsSsid() {
        val msg = CtrlMsg.See("nid32", "DIRECT-ab Phone", "psk;x", 0, 1, "aa")
        val decoded = CtrlCodec.decode(CtrlCodec.encode(msg)) as CtrlMsg.See
        assertEquals("DIRECT-ab Phone", decoded.ssid)
        assertEquals("psk;x", decoded.psk)
        assertEquals(1, decoded.hops)
        assertEquals("aa", decoded.origin)
    }

    @Test
    fun whoRoundTrip() {
        val line = CtrlCodec.encode(CtrlMsg.Who("Who:n", "n", 3, 1, "aa"))
        assertTrue(line.startsWith("WHO"))
        val decoded = CtrlCodec.decode(line) as CtrlMsg.Who
        assertEquals(3, decoded.ttl)
        assertEquals(1, decoded.hops)
        assertEquals("aa", decoded.origin)
    }

    @Test
    fun mergeAndGoingRoundTrip() {
        val merge = CtrlCodec.decode(CtrlCodec.encode(CtrlMsg.Merge("See:cc"))) as CtrlMsg.Merge
        assertEquals("See:cc", merge.eid)
        val going = CtrlCodec.decode(
            CtrlCodec.encode(CtrlMsg.Going("cc", "bb", 2, "aa")),
        ) as CtrlMsg.Going
        assertEquals("bb", going.by)
        assertEquals(2, going.hops)
    }
}
