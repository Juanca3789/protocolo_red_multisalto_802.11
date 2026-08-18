package co.uan.pct.lib.core.internal.ctrl

/**
 * HELLO de control (UDP): el hijo anuncia su UUID PCT al padre SoftAP.
 * Formato: `PCT1|HELLO|<nid32>|<role>|<hop>`
 */
data class HelloPayload(
    val nid: String,
    val role: String,
    val hop: Int,
)

object HelloCodec {
    private const val PREFIX = "PCT1|HELLO|"

    fun encode(payload: HelloPayload): ByteArray {
        val line = "$PREFIX${payload.nid}|${payload.role}|${payload.hop}"
        return line.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray, length: Int = bytes.size): HelloPayload? {
        val text = bytes.decodeToString(0, length).trim()
        if (!text.startsWith(PREFIX)) return null
        val parts = text.removePrefix(PREFIX).split('|')
        if (parts.size < 3) return null
        val nid = parts[0].trim().lowercase()
        if (nid.length != 32 || nid.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val role = parts[1].trim().ifBlank { "CHILD" }
        val hop = parts[2].trim().toIntOrNull() ?: 0
        return HelloPayload(nid = nid, role = role, hop = hop)
    }
}
