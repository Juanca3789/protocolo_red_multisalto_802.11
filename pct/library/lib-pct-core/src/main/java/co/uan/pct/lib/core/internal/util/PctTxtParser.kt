package co.uan.pct.lib.core.internal.util

import co.uan.pct.lib.core.internal.p2p.model.PctCtrlRecord

object PctTxtParser {

    private const val SERVICE_CTRL = "_pct-ctrl._tcp"
    private const val DEFAULT_CTRL_PORT = 8765

    fun isPctCtrlService(registrationType: String?): Boolean {
        return registrationType?.contains(SERVICE_CTRL, ignoreCase = true) == true
    }

    fun parseCtrlRecord(
        txtRecord: Map<String, String>,
        deviceAddress: String,
    ): PctCtrlRecord? {
        val normalized = normalizeTxtRecord(txtRecord)
        if (normalized.isEmpty()) return null

        val nid = pick(normalized, "nid", "n") ?: return null
        val goSsid = pick(normalized, "go_ssid", "gs", "s", "ssid") ?: return null
        val goPsk = pick(normalized, "go_psk", "gp", "p", "psk") ?: ""
        val ctrlPort = pick(normalized, "cp", "c", "port", "ctrl_port")
            ?.toIntOrNull()
            ?: DEFAULT_CTRL_PORT

        if (goSsid.isBlank()) return null

        return PctCtrlRecord(
            nid = nid,
            role = pick(normalized, "role", "r") ?: "UNKNOWN",
            hop = pick(normalized, "hop", "h")?.toIntOrNull() ?: 0,
            epoch = pick(normalized, "epoch", "e")?.toLongOrNull() ?: 0L,
            ctrlPort = ctrlPort,
            goSsid = goSsid,
            goPsk = goPsk,
            deviceAddress = deviceAddress,
        )
    }

    /** TXT compacto para stacks Android que truncan registros largos. */
    fun buildCtrlTxtRecordCompact(
        nid: String,
        goSsid: String,
        goPsk: String,
        ctrlPort: Int = DEFAULT_CTRL_PORT,
    ): Map<String, String> = mapOf(
        "n" to nid,
        "s" to goSsid,
        "p" to goPsk,
        "c" to ctrlPort.toString(),
    )

    fun buildCtrlTxtRecord(
        nid: String,
        role: String,
        hop: Int,
        epoch: Long,
        ctrlPort: Int,
        goSsid: String,
        goPsk: String,
        accepts: Boolean,
    ): Map<String, String> = mapOf(
        "v" to "1",
        "nid" to nid,
        "role" to role,
        "hop" to hop.toString(),
        "epoch" to epoch.toString(),
        "tv" to "1",
        "cp" to ctrlPort.toString(),
        "go_ssid" to goSsid,
        "go_psk" to goPsk,
        "accepts" to if (accepts) "1" else "0",
    )

    fun describeParseFailure(txtRecord: Map<String, String>): String {
        val normalized = normalizeTxtRecord(txtRecord)
        val missing = buildList {
            if (pick(normalized, "nid", "n") == null) add("nid/n")
            if (pick(normalized, "go_ssid", "gs", "s", "ssid") == null) add("go_ssid/s")
            if (pick(normalized, "go_psk", "gp", "p", "psk") == null) add("go_psk/p")
        }
        return when {
            normalized.isEmpty() -> "TXT vacío (listener sin datos)"
            missing.isNotEmpty() -> "faltan: ${missing.joinToString()}"
            else -> "desconocido"
        }
    }

    private fun pick(map: Map<String, String>, vararg keys: String): String? {
        for (key in keys) {
            val value = map[key]?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    /**
     * Normaliza variantes que devuelven algunos OEM:
     * - mapa vacío con datos en un solo blob
     * - claves "key=value" mezcladas
     */
    internal fun normalizeTxtRecord(raw: Map<String, String>): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()

        val merged = linkedMapOf<String, String>()
        for ((key, value) in raw) {
            when {
                key.isBlank() && value.contains('=') -> merged.putAll(parseKeyValueBlob(value))
                key.contains('=') && value.isBlank() -> merged.putAll(parseKeyValueBlob(key))
                else -> merged[key.trim()] = value.trim()
            }
        }
        return merged
    }

    private fun parseKeyValueBlob(blob: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val tokens = blob.split('\u0000', '\n', '&', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (token in tokens) {
            val idx = token.indexOf('=')
            if (idx <= 0) continue
            val key = token.substring(0, idx).trim()
            val value = token.substring(idx + 1).trim()
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }
}
