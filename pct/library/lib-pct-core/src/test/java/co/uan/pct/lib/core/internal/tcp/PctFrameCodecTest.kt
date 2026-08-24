package co.uan.pct.lib.core.internal.tcp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PctFrameCodecTest {

    @Test
    fun hello_roundtrip() {
        val nid = "0123456789abcdef0123456789abcdef"
        val parent = "00000000000000000000000000000000"
        val hello = HelloPayload(
            senderNid = nid,
            role = 3,
            parentNid = parent,
            epoch = 1,
            treeVersion = 1,
            hop = 0,
            capabilities = 3,
            neighborCount = 0,
        )
        val frame = PctFrameCodec.encodeHello(hello)
        assertEquals(59, frame.size)
        val (msgType, len) = PctFrameCodec.decodeHeader(frame.copyOfRange(0, 12))
        assertEquals(PctMsgType.HELLO, msgType)
        assertEquals(47, len)
        val decoded = PctFrameCodec.decodeHello(frame.copyOfRange(12, frame.size))
        assertEquals(nid, decoded.senderNid)
        assertEquals(3, decoded.role)
        assertEquals(parent, decoded.parentNid)
        assertEquals(0, decoded.hop)
    }

    @Test
    fun userData_roundtrip() {
        val msgId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val src = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val dst = "cccccccccccccccccccccccccccccccc"
        val data = "hola L3".toByteArray(Charsets.UTF_8)
        val payload = UserDataPayload(
            msgId = msgId,
            srcNid = src,
            dstNid = dst,
            sessionEpoch = 1,
            hopLimit = 7,
            trace = emptyList(),
            userData = data,
        )
        val frame = PctFrameCodec.encodeUserData(payload)
        val decoded = PctFrameCodec.decodeUserData(frame.copyOfRange(12, frame.size))
        assertEquals(src, decoded.srcNid)
        assertEquals(dst, decoded.dstNid)
        assertArrayEquals(data, decoded.userData)
    }

    @Test
    fun topoUpdate_roundtrip() {
        val origin = "dddddddddddddddddddddddddddddddd"
        val entry = TopoRouteEntry(
            destNid = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            hopCount = 2,
            status = 0,
            pathSeq = 5,
        )
        val topo = TopoUpdatePayload(origin, pathSeq = 10, epoch = 1, ttl = 3, entries = listOf(entry))
        val frame = PctFrameCodec.encodeTopoUpdate(topo)
        val decoded = PctFrameCodec.decodeTopoUpdate(frame.copyOfRange(12, frame.size))
        assertEquals(origin, decoded.originNid)
        assertEquals(1, decoded.entries.size)
        assertEquals(2, decoded.entries[0].hopCount)
    }
}
