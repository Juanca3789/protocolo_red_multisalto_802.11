package co.uan.pct.lib.core.internal.util

/**
 * Codifica SSID/PSK en el instance name DNS-SD porque muchos dispositivos Android
 * entregan "service found" pero nunca llaman al listener de TXT remoto.
 *
 * Formato: `p` + Base64URL(`nid8|ssid|psk`) sin padding (máx. 63 caracteres DNS).
 */
object PctInstanceCodec {

    private const val PREFIX = "p"
    private const val MAX_INSTANCE_LEN = 63
    private const val SEP = '|'

    data class Payload(
        val nidShort: String,
        val goSsid: String,
        val goPsk: String,
    )

    fun encode(nid: String, goSsid: String, goPsk: String): EncodeResult {
        if (goSsid.isBlank()) {
            return EncodeResult.Failure("go_ssid vacío")
        }
        if (goPsk.isBlank()) {
            return EncodeResult.Failure("go_psk vacío — pulsa Info GO en Host y re-anuncia")
        }
        val nidShort = nid.take(8)
        val raw = "$nidShort$SEP$goSsid$SEP$goPsk"
        val encoded = android.util.Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        val instance = PREFIX + encoded
        if (instance.length > MAX_INSTANCE_LEN) {
            return EncodeResult.Failure(
                "instance demasiado largo (${instance.length}>$MAX_INSTANCE_LEN): " +
                    "ssid_len=${goSsid.length} psk_len=${goPsk.length}",
            )
        }
        return EncodeResult.Success(instance, raw.length)
    }

    fun decode(instanceName: String): Payload? {
        if (!instanceName.startsWith(PREFIX) || instanceName.length <= 1) {
            return decodeLegacy(instanceName)
        }
        return runCatching {
            val raw = String(
                android.util.Base64.decode(
                    instanceName.substring(1),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
                ),
                Charsets.UTF_8,
            )
            val parts = raw.split(SEP, limit = 3)
            if (parts.size != 3) return null
            val (nidShort, ssid, psk) = parts
            if (nidShort.isBlank() || ssid.isBlank() || psk.isBlank()) return null
            Payload(nidShort = nidShort, goSsid = ssid, goPsk = psk)
        }.getOrNull()
    }

  /** Formato antiguo pct-{nid8}: sin credenciales embebidas. */
    private fun decodeLegacy(instanceName: String): Payload? {
        if (!instanceName.startsWith("pct-")) return null
        return null
    }

    sealed interface EncodeResult {
        data class Success(val instanceName: String, val rawBytes: Int) : EncodeResult
        data class Failure(val reason: String) : EncodeResult
    }
}
