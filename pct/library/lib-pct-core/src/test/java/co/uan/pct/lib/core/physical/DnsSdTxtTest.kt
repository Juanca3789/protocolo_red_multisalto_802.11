package co.uan.pct.lib.core.physical

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalUuidApi::class)
class DnsSdTxtTest {

    private val nid = Uuid.parse("a3f21b7c-4d5e-6f80-9112-131415161718")

    @Test
    fun encode_usesSnakeCaseKeys() {
        val struct = ServiceStructure(
            nid = nid,
            role = Role.ROOT,
            depth = 0,
            ctrlPort = 8765,
            goSsid = "DIRECT-PCT-a3f21b7c",
            goPsk = "secret1234567890",
            childCount = 2,
        )
        val txt = encodeDnsSdTxt(struct)
        assertEquals("8765", txt["cp"])
        assertEquals("DIRECT-PCT-a3f21b7c", txt["go_ssid"])
        assertEquals("secret1234567890", txt["go_psk"])
        assertEquals("2", txt["child_count"])
        assertEquals("0", txt["depth"])
        assertEquals("ROOT", txt["role"])
    }

    @Test
    fun decode_roundTrip() {
        val struct = ServiceStructure(
            nid = nid,
            role = Role.BRIDGE,
            depth = 1,
            ctrlPort = 8765,
            goSsid = "DIRECT-PCT-b7c24d5e",
            goPsk = "psk123456789012",
            childCount = 0,
            p2pDeviceAddress = "aa:bb:cc:dd:ee:ff",
        )
        val decoded = decodeDnsSdTxt(encodeDnsSdTxt(struct), "aa:bb:cc:dd:ee:ff")
        assertNotNull(decoded)
        assertEquals(struct.nid, decoded!!.nid)
        assertEquals(struct.goSsid, decoded.goSsid)
        assertEquals(struct.depth, decoded.depth)
        assertEquals(Role.BRIDGE, decoded.role)
    }

    @Test
    fun decode_acceptsHopAliasAndLeafAsBridge() {
        val txt = mapOf(
            "v" to "1",
            "nid" to "a3f21b7c4d5e6f809112131415161718",
            "role" to "LEAF",
            "hop" to "2",
            "cp" to "8765",
            "go_ssid" to "ssid",
            "go_psk" to "psk123456789012",
        )
        val decoded = decodeDnsSdTxt(txt, "00:11:22:33:44:55")
        assertNotNull(decoded)
        assertEquals(2, decoded!!.depth)
        assertEquals(Role.BRIDGE, decoded.role)
    }

    @Test
    fun decode_rejectsMissingCredentials() {
        val txt = mapOf(
            "nid" to "a3f21b7c4d5e6f809112131415161718",
            "role" to "ROOT",
            "depth" to "0",
            "cp" to "8765",
        )
        assertNull(decodeDnsSdTxt(txt, ""))
    }

    @Test
    fun instance_isShortPctNameSoTxtFits() {
        val struct = ServiceStructure(
            nid = nid,
            role = Role.ROOT,
            depth = 0,
            ctrlPort = 8765,
            goSsid = "DIRECT-ab",
            goPsk = "psk12345",
            childCount = 0,
        )
        assertEquals("pct-a3f21b7c", encodeDnsSdInstance(struct))
    }

    @Test
    fun instance_packedStillDecodes() {
        val struct = ServiceStructure(
            nid = nid,
            role = Role.ROOT,
            depth = 0,
            ctrlPort = 8765,
            goSsid = "DIRECT-ab",
            goPsk = "psk12345",
            childCount = 0,
        )
        val packed = "p" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("a3f21b7c|DIRECT-ab|psk12345".toByteArray(Charsets.UTF_8))
        val decoded = decodeDnsSdInstance(packed, "aa:bb")
        assertNotNull(decoded)
        assertEquals(struct.goSsid, decoded!!.goSsid)
        assertEquals(struct.goPsk, decoded.goPsk)
    }
}
