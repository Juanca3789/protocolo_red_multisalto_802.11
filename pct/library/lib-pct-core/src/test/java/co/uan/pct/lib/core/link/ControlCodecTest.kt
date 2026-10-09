package co.uan.pct.lib.core.link

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
        val ping = ControlCodec.encodePing(42)
        val pong = ControlCodec.encodePong(42)
        assertTrue(ControlCodec.decode(ping) is ControlCodec.Frame.Ping)
        assertEquals(42, (ControlCodec.decode(ping) as ControlCodec.Frame.Ping).seq)
        assertEquals(42, (ControlCodec.decode(pong) as ControlCodec.Frame.Pong).seq)
    }

    @Test
    fun roundTrip_announce() {
        val id = NodeId(Uuid.random(), "pct_Pixel_8")
        val frame = ControlCodec.encodeAnnounce(id, "192.168.49.55")
        val decoded = ControlCodec.decode(frame) as ControlCodec.Frame.Announce
        assertEquals(id.identifier, decoded.payload.nodeId.identifier)
        assertEquals(id.name, decoded.payload.nodeId.name)
        assertEquals("192.168.49.55", decoded.payload.ipv4OnLink)
    }

    @Test
    fun roundTrip_announceOk() {
        val id = NodeId(Uuid.random(), "root_A24")
        val frame = ControlCodec.encodeAnnounceOk(id, "192.168.49.1")
        val decoded = ControlCodec.decode(frame) as ControlCodec.Frame.AnnounceOk
        assertEquals(id.name, decoded.payload.nodeId.name)
        assertEquals("192.168.49.1", decoded.payload.ipv4OnLink)
    }
}
