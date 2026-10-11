package co.uan.pct.lib.core.data.codec

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalUuidApi::class)
class DataCodecTest {
    @Test
    fun roundTrip_hello() {
        val id = Uuid.random()
        val decoded = readDataPacket(DataPacket.Hello(id).toWireBytes()) as DataPacket.Hello
        assertEquals(id, decoded.nodeUuid)
    }

    @Test
    fun roundTrip_bcast() {
        val origin = Uuid.random()
        val payload = "hola".toByteArray()
        val frame = DataPacket.Bcast(7, origin, 1_700_000_000_000, payload).toWireBytes()
        assertEquals(0x50.toByte(), frame[0])
        assertEquals(0x43.toByte(), frame[1])
        assertEquals(0x54.toByte(), frame[2])
        assertEquals(0x44.toByte(), frame[3])
        assertEquals(DataPacketKind.BCAST.code, frame[4])
        val decoded = readDataPacket(frame) as DataPacket.Bcast
        assertEquals(7, decoded.msgId)
        assertEquals(origin, decoded.originUuid)
        assertEquals(1_700_000_000_000, decoded.tsMs)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun roundTrip_bcastRecv() {
        val origin = Uuid.random()
        val decoded = readDataPacket(DataPacket.BcastRecv(3, origin).toWireBytes()) as DataPacket.BcastRecv
        assertEquals(3, decoded.msgId)
        assertEquals(origin, decoded.originUuid)
        assertTrue(decoded.toWireBytes()[4] == DataPacketKind.BCAST_RECV.code)
    }
}
