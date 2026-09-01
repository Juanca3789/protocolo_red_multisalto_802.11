package co.uan.pct.lib.core.internal.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

class PctNetworkHelperTest {

    @Test
    fun normalizeHost_stripsZoneId() {
        assertEquals("192.168.49.2", PctNetworkHelper.normalizeHost("192.168.49.2%wlan0"))
    }

    @Test
    fun remoteIp_fromInetSocketAddress() {
        val socket = java.net.Socket()
        // Cannot connect in unit test; test normalizeIp directly
        val addr = InetSocketAddress("192.168.49.3", 8765)
        assertEquals("192.168.49.3", PctNetworkHelper.normalizeIp(addr))
    }
}
