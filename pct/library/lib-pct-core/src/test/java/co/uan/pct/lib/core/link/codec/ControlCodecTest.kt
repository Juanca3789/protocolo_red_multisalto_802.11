package co.uan.pct.lib.core.link.codec

import co.uan.pct.lib.core.types.NodeId
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalUuidApi::class)
class ControlCodecTest {
    @Test
    fun roundTrip_pingPong() {
        val ping = ControlPacket.Ping(42).toWireBytes()
        val pong = ControlPacket.Pong(42).toWireBytes()
        assertTrue(readControlPacket(ping) is ControlPacket.Ping)
        assertEquals(42, (readControlPacket(ping) as ControlPacket.Ping).seq)
        assertEquals(42, (readControlPacket(pong) as ControlPacket.Pong).seq)
    }

    @Test
    fun roundTrip_announce() {
        val id = NodeId(Uuid.random(), "pct_Pixel_8")
        val frame = ControlPacket.Announce(id, "192.168.49.55").toWireBytes()
        val decoded = readControlPacket(frame) as ControlPacket.Announce
        assertEquals(id.identifier, decoded.nodeId.identifier)
        assertEquals(id.name, decoded.nodeId.name)
        assertEquals("192.168.49.55", decoded.ipv4OnLink)
    }

    @Test
    fun roundTrip_announceOk() {
        val id = NodeId(Uuid.random(), "root_A24")
        val frame = ControlPacket.AnnounceOk(id, "192.168.49.1").toWireBytes()
        val decoded = readControlPacket(frame) as ControlPacket.AnnounceOk
        assertEquals(id.name, decoded.nodeId.name)
        assertEquals("192.168.49.1", decoded.ipv4OnLink)
    }
}
