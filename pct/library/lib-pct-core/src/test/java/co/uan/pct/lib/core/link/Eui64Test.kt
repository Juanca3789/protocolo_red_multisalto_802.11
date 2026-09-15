package co.uan.pct.lib.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `fe80::` EUI-64 desde el BSSID del GO padre. Los vectores son de los logs reales:
 * la interfaz de grupo `be:32:b2:48:1b:c5` del A24 formaba `fe80::bc32:b2ff:fe48:1bc5`.
 */
class Eui64Test {

    private fun format(bytes: ByteArray): String {
        val groups = (0 until 8).map { i ->
            ((bytes[i * 2].toInt() and 0xff) shl 8) or (bytes[i * 2 + 1].toInt() and 0xff)
        }
        return groups.joinToString(":") { Integer.toHexString(it) }
            .replace(Regex("(^|:)0(:0)+(:|$)"), "::")
    }

    @Test
    fun a24GroupInterfaceBssid() {
        val bytes = eui64LinkLocal("be:32:b2:48:1b:c5")!!
        assertEquals("fe80::bc32:b2ff:fe48:1bc5", format(bytes))
    }

    @Test
    fun flipsUniversalLocalBitAndInsertsFffe() {
        val bytes = eui64LinkLocal("02:0a:f5:db:7c:d6")!!
        // 02 xor 02 = 00 → primer octeto del id vuelve a 00.
        assertEquals("fe80::a:f5ff:fedb:7cd6", format(bytes))
    }

    @Test
    fun acceptsDashSeparatedMac() {
        val colon = eui64LinkLocal("be:32:b2:48:1b:c5")
        val dash = eui64LinkLocal("be-32-b2-48-1b-c5")
        assertEquals(format(colon!!), format(dash!!))
    }

    @Test
    fun rejectsMalformedMac() {
        assertNull(eui64LinkLocal("no-es-mac"))
        assertNull(eui64LinkLocal("be:32:b2:48:1b"))
        assertNull(eui64LinkLocal("be:32:b2:48:1b:zz"))
    }
}
