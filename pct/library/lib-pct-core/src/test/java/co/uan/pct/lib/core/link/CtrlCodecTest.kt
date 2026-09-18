package co.uan.pct.lib.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun hiWithoutRoutesRoundTrip() {
        val decoded = CtrlCodec.decode(CtrlCodec.encode(CtrlMsg.Hi("aa", 0, "aa", emptyList()))) as CtrlMsg.Hi
        assertEquals("aa", decoded.nid)
        assertTrue(decoded.routes.isEmpty())
    }

    @Test
    fun tabRoundTrip() {
        val decoded = CtrlCodec.decode(CtrlCodec.encode(CtrlMsg.Tab(listOf("bb" to 1)))) as CtrlMsg.Tab
        assertEquals(listOf("bb" to 1), decoded.routes)
    }

    @Test
    fun pingPongRoundTrip() {
        assertEquals(7, (CtrlCodec.decode(CtrlCodec.encode(CtrlMsg.Ping(7))) as CtrlMsg.Ping).seq)
        assertEquals(9, (CtrlCodec.decode(CtrlCodec.encode(CtrlMsg.Pong(9))) as CtrlMsg.Pong).seq)
    }

    @Test
    fun unknownLineIsIgnored() {
        assertNull(CtrlCodec.decode("HOLA mundo"))
        assertNull(CtrlCodec.decode(""))
        assertNull(CtrlCodec.decode("   "))
    }
}
