package co.uan.pct.lib.core.internal.util

/** Identificadores de nodo: 32 hex en wire/L3; 8 hex prefijo solo en DNS instance (provisional). */
internal object PctNid {
    private val HEX32 = Regex("^[0-9a-fA-F]{32}$")
    private val HEX8 = Regex("^[0-9a-fA-F]{8}$")

    fun isFull(nid: String): Boolean =
        HEX32.matches(nid) && !isZeroPaddedPrefix(nid)

    /** Rechaza UUIDs falsos tipo `88ccb6f3000000000000000000000000` del antiguo padEnd(32,'0'). */
    fun isZeroPaddedPrefix(nid: String): Boolean =
        nid.length == 32 && HEX8.matches(nid.take(8)) &&
            nid.drop(8).all { it == '0' }

    fun isPrefix(nid: String): Boolean = HEX8.matches(nid)

    fun normalize(nid: String): String = nid.lowercase()

    /** Nid usable en frames PCT (32 hex). Null si solo hay prefijo DNS. */
    fun forWire(nid: String?): String? = nid?.takeIf { isFull(it) }?.let { normalize(it) }

    fun matches(a: String, b: String): Boolean {
        if (isFull(a) && isFull(b)) return normalize(a) == normalize(b)
        return a.take(8).equals(b.take(8), ignoreCase = true)
    }
}
