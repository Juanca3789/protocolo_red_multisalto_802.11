package co.uan.pct.lib.core.link

import co.uan.pct.lib.core.net.UserMessage
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El `LinkLayer` de producción sobre TCP real en 127.0.0.1. Cada nodo escucha en puertos
 * efímeros; el "STA" es el [Uplink] que apunta al puerto de control del padre. Sin Android,
 * sin Wi‑Fi: se ejercita exactamente la máquina de enlace que corre en el teléfono.
 */
class LinkLayerLoopbackTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    private inner class Node(val id: String) : AutoCloseable {
        val uplink = MutableStateFlow<Uplink?>(null)
        val loops = java.util.concurrent.CopyOnWriteArrayList<String>()
        val link = LinkLayer(
            nodeId = id,
            uplinks = uplink,
            onLoop = { loops += it; uplink.value = null },
            ctrlPort = 0,
            dataPort = 0,
            bindAddress = loopback,
            keepAliveMs = 200,
            deadAfterMs = 600,
        )

        fun start() = link.start()

        /** Este nodo se vuelve hijo de [parent] (como si la STA se hubiera asociado). */
        fun attachTo(parent: Node) {
            uplink.value = Uplink(
                parentNid = parent.id,
                address = loopback,
                ctrlPort = parent.link.boundCtrlPort,
                dataPort = parent.link.boundDataPort,
            )
        }

        fun detach() {
            uplink.value = null
        }

        override fun close() = link.close()
    }

    private suspend fun CoroutineScope.listen(node: Node): Deferred<UserMessage> {
        val pending = async { node.link.mesh.inbox.first() }
        delay(30)
        return pending
    }

    private suspend fun waitUntil(timeoutMs: Long, ok: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (ok()) return true
            delay(20)
        }
        return ok()
    }

    /** Reintenta el envío hasta que la tabla y el `:8766` estén listos (send devuelve true). */
    private suspend fun sendWhenReady(from: Node, dest: String, text: String, timeoutMs: Long = 3_000): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (from.link.mesh.send(dest, text.toByteArray())) return true
            delay(30)
        }
        return false
    }

    @Test
    fun twoNodesChatBothWays() = runBlocking {
        val a = Node(NID_A)
        val b = Node(NID_B)
        try {
            a.start(); b.start()
            b.attachTo(a)
            assertTrue(
                "las tablas deben converger",
                waitUntil(2_000) {
                    a.link.snapshot.value.neighbors.isNotEmpty() &&
                        b.link.snapshot.value.neighbors.isNotEmpty()
                },
            )

            val atB = listen(b)
            assertTrue(sendWhenReady(a, NID_B, "hola-B"))
            assertArrayEquals("hola-B".toByteArray(), withTimeout(2_000) { atB.await() }.payload)

            val atA = listen(a)
            assertTrue(sendWhenReady(b, NID_A, "hola-A"))
            assertArrayEquals("hola-A".toByteArray(), withTimeout(2_000) { atA.await() }.payload)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun threeNodesRelayMultihop() = runBlocking {
        val a = Node(NID_A)
        val b = Node(NID_B)
        val c = Node(NID_C)
        try {
            a.start(); b.start(); c.start()
            b.attachTo(a)
            c.attachTo(b)
            assertTrue(
                "A debe aprender la ruta a C por B",
                waitUntil(3_000) {
                    a.link.snapshot.value.routes.any { it.dest == NID_C && it.hops == 2 } &&
                        c.link.snapshot.value.routes.any { it.dest == NID_A && it.hops == 2 }
                },
            )

            val atC = listen(c)
            assertTrue(sendWhenReady(a, NID_C, "a-hasta-c"))
            assertArrayEquals("a-hasta-c".toByteArray(), withTimeout(2_000) { atC.await() }.payload)

            val atA = listen(a)
            assertTrue(sendWhenReady(c, NID_A, "c-hasta-a"))
            assertArrayEquals("c-hasta-a".toByteArray(), withTimeout(2_000) { atA.await() }.payload)
        } finally {
            a.close(); b.close(); c.close()
        }
    }

    @Test
    fun neighborDropRemovesRoutes() = runBlocking {
        val a = Node(NID_A)
        val b = Node(NID_B)
        val c = Node(NID_C)
        try {
            a.start(); b.start(); c.start()
            b.attachTo(a)
            c.attachTo(b)
            assertTrue(waitUntil(3_000) { a.link.snapshot.value.routes.any { it.dest == NID_C } })

            c.close()
            assertTrue(
                "al caer C, A ya no debe tener ruta a C",
                waitUntil(3_000) { a.link.snapshot.value.routes.none { it.dest == NID_C } },
            )
        } finally {
            a.close(); b.close(); c.close()
        }
    }

    @Test
    fun mutualAttachResolvesToSingleEdge() = runBlocking {
        val a = Node(NID_A)
        val b = Node(NID_B)
        try {
            a.start(); b.start()
            // Los dos se ven y los dos hacen "STA" al otro: bucle. Debe quedar una sola arista.
            a.attachTo(b)
            b.attachTo(a)
            assertTrue(
                "el nid menor debe soltar su STA por el bucle",
                waitUntil(3_000) { a.loops.isNotEmpty() || b.loops.isNotEmpty() },
            )
            val converged = waitUntil(3_000) {
                val aSnap = a.link.snapshot.value
                val bSnap = b.link.snapshot.value
                aSnap.neighbors == listOf(NID_B) &&
                    bSnap.neighbors == listOf(NID_A) &&
                    listOfNotNull(aSnap.parentId, bSnap.parentId).size == 1
            }
            assertTrue(
                "una sola arista y un solo sentido; A=${a.link.snapshot.value} B=${b.link.snapshot.value} loopsA=${a.loops} loopsB=${b.loops}",
                converged,
            )
            // Por la regla del nid, el menor (A) es el padre y B el hijo.
            assertEquals("A es padre", null, a.link.snapshot.value.parentId)
            assertEquals("B es hijo de A", NID_A, b.link.snapshot.value.parentId)

            val atB = listen(b)
            assertTrue(sendWhenReady(a, NID_B, "sin-bucle"))
            assertArrayEquals("sin-bucle".toByteArray(), withTimeout(2_000) { atB.await() }.payload)
        } finally {
            a.close(); b.close()
        }
    }

    private companion object {
        const val NID_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val NID_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val NID_C = "cccccccccccccccccccccccccccccccc"
    }
}
