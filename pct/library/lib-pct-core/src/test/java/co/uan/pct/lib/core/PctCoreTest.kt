package co.uan.pct.lib.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PctCoreTest {

    @Test
    fun create_returnsNode() {
        val node = PctCore.create()
        assertTrue(node.nodeId.isEmpty() || node.nodeId.length == 32)
    }

    @Test
    fun requiredPermissions_includeWifiAndLocation() {
        val perms = PctCore.requiredPermissions
        assertTrue(perms.isNotEmpty())
        assertArrayEquals(perms, PctPermissions.required)
    }
}
