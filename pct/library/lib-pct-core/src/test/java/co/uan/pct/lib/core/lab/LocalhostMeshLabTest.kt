package co.uan.pct.lib.core.lab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Laboratorio mínimo “real”: dos bosques en 127.0.0.1 con TCP de verdad.
 * A–B–C y D–E; A se engancha a D (brazo libre) y C habla con E.
 */
class LocalhostMeshLabTest {

    @Test
    fun twoTreesJoinOnLocalhostTcp() = runBlocking {
        val a = JvmNode(LabIds.A)
        val b = JvmNode(LabIds.B)
        val c = JvmNode(LabIds.C)
        val d = JvmNode(LabIds.D)
        val e = JvmNode(LabIds.E)
        val nodes = listOf(a, b, c, d, e)
        try {
            nodes.forEach { it.start() }
            delay(40)
            b.attachTo(a)
            c.attachTo(b)
            e.attachTo(d)
            delay(200)
            assertTrue(c.table.known(LabIds.A))
            assertTrue(e.table.known(LabIds.D))
            assertFalse(c.table.known(LabIds.E))

            a.attachTo(d)
            waitUntil(1_500) { c.table.known(LabIds.E) && e.table.known(LabIds.C) }
            assertTrue(c.table.known(LabIds.E))
            assertTrue(e.table.known(LabIds.C))

            val atE = listen(e)
            assertTrue(c.mesh.send(LabIds.E, "lab-real".toByteArray()))
            assertArrayEquals(
                "lab-real".toByteArray(),
                withTimeout(1_500) { atE.await() }.payload,
            )
        } finally {
            nodes.forEach { it.close() }
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, ok: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (ok()) return
            delay(25)
        }
    }

    private suspend fun CoroutineScope.listen(node: JvmNode): Deferred<co.uan.pct.lib.core.net.UserMessage> {
        val pending = async { node.mesh.inbox.first() }
        delay(25)
        return pending
    }
}
