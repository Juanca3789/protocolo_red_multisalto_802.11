package co.uan.pct.lib.core.radio.advertise

import android.util.Base64

/**
 * EXP-01: credenciales en instance (`p` + Base64URL) porque el TXT remoto suele no llegar.
 * Ver [docs/prototype/proto-exp01-gostat/spec-mdns-dns-sd.md].
 */
object PctInstanceCodec {
    private const val PREFIX = 'p'
    private const val MAX_INSTANCE_LEN = 63
    private const val SEP = '|'

    data class Payload(
        val nidShort: String,
        val goSsid: String,
        val goPsk: String,
    )

    fun encode(nidHex32: String, goSsid: String, goPsk: String): EncodeResult {
        if (goSsid.isBlank()) return EncodeResult.Failure("go_ssid vacío")
        if (goPsk.isBlank()) return EncodeResult.Failure("go_psk vacío")
        val nidShort = nidHex32.replace("-", "").lowercase().take(8)
        val raw = "$nidShort$SEP$goSsid$SEP$goPsk"
        val encoded = Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val instance = "$PREFIX$encoded"
        if (instance.length > MAX_INSTANCE_LEN) {
            return EncodeResult.Failure("instance > $MAX_INSTANCE_LEN chars")
        }
        return EncodeResult.Success(instance)
    }

    fun decode(instanceName: String): Payload? {
        if (!instanceName.startsWith(PREFIX) || instanceName.length <= 1) {
            return null
        }
        return runCatching {
            val raw = String(
                Base64.decode(
                    instanceName.substring(1),
                    Base64.URL_SAFE or Base64.NO_WRAP,
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

    sealed interface EncodeResult {
        data class Success(val instanceName: String) : EncodeResult
        data class Failure(val reason: String) : EncodeResult
    }
}
