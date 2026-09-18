package co.uan.pct.lib.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshSocketTest {

    private class FakeHops : HopTable {
        var next: String? = "bb"
        val written = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun nextNid(dest: String): String? = next
        override suspend fun writeNext(nextNid: String, bytes: ByteArray): Boolean {
            written += nextNid to bytes
            return true
        }
    }

    @Test
    fun sendUsesNextHopNotDestinationIp() = runBlocking {
        val hops = FakeHops()
        val socket = MeshSocket("aa", hops)
        assertTrue(socket.send("cc", "hola".toByteArray()))
        assertEquals("bb", hops.written.single().first)
        val frame = UserCodec.decode(hops.written.single().second)!!
        assertEquals("aa".padEnd(32, '0'), frame.src)
        assertEquals("cc".padEnd(32, '0'), frame.dst)
        assertEquals(7, frame.ttl)
        assertArrayEquals("hola".toByteArray(), frame.payload)
    }

    @Test
    fun noRouteDoesNotInventFlood() = runBlocking {
        val hops = FakeHops().also { it.next = null }
        assertFalse(MeshSocket("aa", hops).send("cc", byteArrayOf(1)))
        assertTrue(hops.written.isEmpty())
    }

    @Test
    fun doesNotBounceToIncomingNeighbor() = runBlocking {
        val hops = FakeHops().also { it.next = "bb" }
        val socket = MeshSocket("aa", hops)
        val frame = UserFrame("cc", "dd", 5, byteArrayOf(9))
        assertFalse(socket.onHop("bb", frame))
        assertTrue(hops.written.isEmpty())
    }

    @Test
    fun ttlOneIsDropped() = runBlocking {
        val hops = FakeHops()
        val socket = MeshSocket("aa", hops)
        assertFalse(socket.onHop("bb", UserFrame("cc", "dd", 1, byteArrayOf(1))))
        assertTrue(hops.written.isEmpty())
    }

    @Test
    fun codecRoundTrip() {
        val frame = UserFrame("aa", "bb", 4, "x".toByteArray())
        val again = UserCodec.decode(UserCodec.encode(frame))!!
        assertTrue(UserCodec.sameNid(again.src, "aa"))
        assertTrue(UserCodec.sameNid(again.dst, "bb"))
        assertEquals(4, again.ttl)
    }
}
