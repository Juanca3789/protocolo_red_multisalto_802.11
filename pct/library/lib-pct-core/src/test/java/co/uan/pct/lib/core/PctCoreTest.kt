package co.uan.pct.lib.core

import co.uan.pct.lib.core.api.PctConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PctCoreTest {

    @Test
    fun create_returnsPctNode() {
        val node = PctCore.create()
        assertTrue(node.nodeId.isEmpty())
    }

    @Test
    fun config_defaults() {
        val config = PctConfig()
        assertEquals(5_000L, config.scanSettleMs)
        assertTrue(config.autoActivateGoAfterSta)
        assertEquals(8765, config.ctrlPort)
        assertEquals(8766, config.dataPort)
        assertEquals(5_000L, config.pingIntervalMs)
    }
}
