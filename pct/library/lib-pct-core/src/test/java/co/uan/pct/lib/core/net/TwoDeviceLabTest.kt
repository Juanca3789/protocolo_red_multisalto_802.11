package co.uan.pct.lib.core.net

import co.uan.pct.lib.core.link.CtrlCodec
import co.uan.pct.lib.core.link.CtrlMsg
import co.uan.pct.lib.core.link.EventWalks
import co.uan.pct.lib.core.link.RouteTable
import co.uan.pct.lib.core.link.WalkArrival
import co.uan.pct.lib.core.link.WalkKind
import java.io.PipedInputStream
import java.io.PipedOutputStream
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

/**
 * Laboratorio controlado: dos (y tres) nodos con tabla L2 + MeshSocket.
 * El “:8766” es un deliver en memoria; no hay radios ni Android.
 */
class TwoDeviceLabTest {

    private class Lab {
        private val nodes = linkedMapOf<String, Node>()

        fun node(id: String): Node = nodes.getOrPut(id) { Node(id, this) }

        suspend fun deliver(from: String, to: String, bytes: ByteArray): Boolean {
            val dest = nodes[to] ?: return false
            val frame = UserCodec.decode(bytes) ?: return false
            return dest.mesh.onHop(from, frame)
        }
    }

    private class Node(
        val id: String,
        private val lab: Lab,
    ) {
        val table = RouteTable(id)
        val mesh = MeshSocket(
            id,
            object : HopTable {
                override suspend fun nextNid(dest: String): String? =
                    table.find(dest)?.next?.takeUnless { UserCodec.sameNid(it, id) }

                override suspend fun writeNext(nextNid: String, bytes: ByteArray): Boolean =
                    lab.deliver(id, nextNid, bytes)
            },
        )

        fun hear(other: Node, asParent: Boolean) {
            table.installNeighbor(other.id, "10.0.0.${other.id.first().code % 250}", asParent)
        }

        fun exchangeTabs(other: Node) {
            table.mergeFrom(other.id, other.table.advertise())
            other.table.mergeFrom(id, table.advertise())
        }
    }

    private suspend fun CoroutineScope.listen(node: Node): Deferred<UserMessage> {
        val pending = async { node.mesh.inbox.first() }
        delay(25)
        return pending
    }

    @Test
    fun twoDevicesHelloEachOther() = runBlocking {
        val lab = Lab()
        val a = lab.node(NID_A)
        val b = lab.node(NID_B)
        handshakeLikePhones(a, b)

        val incoming = listen(b)
        assertTrue("A debe encontrar ruta a B", a.mesh.send(NID_B, "hola-B".toByteArray()))
        val msg = withTimeout(1_000) { incoming.await() }
        assertTrue(UserCodec.sameNid(msg.from, NID_A))
        assertArrayEquals("hola-B".toByteArray(), msg.payload)
    }

    @Test
    fun twoDevicesReplyOnSamePath() = runBlocking {
        val lab = Lab()
        val a = lab.node(NID_A)
        val b = lab.node(NID_B)
        handshakeLikePhones(a, b)

        val toB = listen(b)
        assertTrue(a.mesh.send(NID_B, "ping".toByteArray()))
        assertArrayEquals("ping".toByteArray(), withTimeout(1_000) { toB.await() }.payload)

        val toA = listen(a)
        assertTrue(b.mesh.send(NID_A, "pong".toByteArray()))
        assertArrayEquals("pong".toByteArray(), withTimeout(1_000) { toA.await() }.payload)
    }

    @Test
    fun twoDevicesWithoutRouteDoNotTalk() = runBlocking {
        val lab = Lab()
        val a = lab.node(NID_A)
        lab.node(NID_B)
        assertFalse(a.mesh.send(NID_B, "x".toByteArray()))
    }

    @Test
    fun threeDevicesMiddleRelays() = runBlocking {
        val lab = Lab()
        val a = lab.node(NID_A)
        val b = lab.node(NID_B)
        val c = lab.node(NID_C)
        chainAbc(a, b, c)

        assertEquals(NID_B, a.table.find(NID_C)?.next)
        assertEquals(2, a.table.find(NID_C)?.hops)
        assertEquals(NID_B, c.table.find(NID_A)?.next)

        val atC = listen(c)
        val leaked = async {
            runCatching { withTimeout(200) { b.mesh.inbox.first() } }.getOrNull()
        }
        delay(25)
        assertTrue(a.mesh.send(NID_C, "via-B".toByteArray()))
        assertArrayEquals("via-B".toByteArray(), withTimeout(1_000) { atC.await() }.payload)
        assertTrue("B reenvía, no entrega", leaked.await() == null)
    }

    @Test
    fun threeDevicesReverseAlsoRelays() = runBlocking {
        val lab = Lab()
        val a = lab.node(NID_A)
        val b = lab.node(NID_B)
        val c = lab.node(NID_C)
        chainAbc(a, b, c)

        val atA = listen(a)
        assertTrue(c.mesh.send(NID_A, "desde-C".toByteArray()))
        assertArrayEquals("desde-C".toByteArray(), withTimeout(1_000) { atA.await() }.payload)
    }

    @Test
    fun twoDevicesHiTabOnTheWire() {
        val hiA = CtrlMsg.Hi(NID_A, 0, NID_A, emptyList())
        val hiB = CtrlMsg.Hi(NID_B, 0, NID_B, emptyList())
        val aSeesB = CtrlCodec.decode(CtrlCodec.encode(hiB)) as CtrlMsg.Hi
        val bSeesA = CtrlCodec.decode(CtrlCodec.encode(hiA)) as CtrlMsg.Hi
        assertEquals(NID_B, aSeesB.nid)
        assertEquals(NID_A, bSeesA.nid)

        val tableA = RouteTable(NID_A)
        val tableB = RouteTable(NID_B)
        tableA.installNeighbor(aSeesB.nid, "192.168.49.20", asParent = false)
        tableB.installNeighbor(bSeesA.nid, "192.168.49.1", asParent = true)
        tableA.mergeFrom(NID_B, aSeesB.routes)
        tableB.mergeFrom(NID_A, bSeesA.routes)
        val tabA = CtrlCodec.decode(CtrlCodec.encode(CtrlMsg.Tab(tableA.advertise()))) as CtrlMsg.Tab
        tableB.mergeFrom(NID_A, tabA.routes)
        assertTrue(tableA.known(NID_B))
        assertTrue(tableB.known(NID_A))
        assertEquals(1, tableA.find(NID_B)?.hops)
        assertEquals(1, tableB.find(NID_A)?.hops)
    }

    @Test
    fun twoPipesOn8766Framing() {
        val aToB = PipedOutputStream()
        val bIn = PipedInputStream(aToB, 8_192)
        val frame = UserFrame(NID_A, NID_B, 7, "stream".toByteArray())
        aToB.write(UserCodec.encode(frame))
        aToB.flush()
        val got = UserCodec.read(bIn)!!
        assertTrue(UserCodec.sameNid(got.src, NID_A))
        assertTrue(UserCodec.sameNid(got.dst, NID_B))
        assertArrayEquals("stream".toByteArray(), got.payload)
        aToB.close()
        bIn.close()
    }

    @Test
    fun twoObserversSameWhoMergeNotStorm() {
        val hops = { _: String -> 1 }
        val phoneA = EventWalks()
        val phoneB = EventWalks()
        phoneA.begin(WalkKind.Who, NID_C, NID_A, 0, listOf(NID_B), null, hops)
        phoneB.begin(WalkKind.Who, NID_C, NID_B, 0, listOf(NID_A), null, hops)
        val atA = phoneA.arrive(
            WalkKind.Who, NID_C, NID_B, NID_B, 1,
            listOf(NID_B), null, hops,
        )
        val atB = phoneB.arrive(
            WalkKind.Who, NID_C, NID_A, NID_A, 1,
            listOf(NID_A), null, hops,
        )
        val aStops = atA is WalkArrival.Keep || atA is WalkArrival.Loop
        val bStops = atB is WalkArrival.Keep || atB is WalkArrival.Loop || atB is WalkArrival.Yield
        assertTrue("A no duplica la onda", aStops)
        assertTrue("B no duplica la onda", bStops)
        assertFalse(atA is WalkArrival.Fresh && atB is WalkArrival.Fresh)
    }

    private fun handshakeLikePhones(a: Node, b: Node) {
        a.hear(b, asParent = false)
        b.hear(a, asParent = true)
        a.exchangeTabs(b)
        assertTrue(a.table.known(b.id))
        assertTrue(b.table.known(a.id))
    }

    private fun chainAbc(a: Node, b: Node, c: Node) {
        a.hear(b, asParent = false)
        b.hear(a, asParent = true)
        b.hear(c, asParent = false)
        c.hear(b, asParent = true)
        b.exchangeTabs(a)
        b.exchangeTabs(c)
        a.table.mergeFrom(NID_B, b.table.advertise())
        c.table.mergeFrom(NID_B, b.table.advertise())
    }

    private companion object {
        const val NID_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val NID_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val NID_C = "cccccccccccccccccccccccccccccccc"
    }
}
