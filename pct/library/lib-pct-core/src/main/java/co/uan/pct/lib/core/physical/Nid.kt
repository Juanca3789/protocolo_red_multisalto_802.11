package co.uan.pct.lib.core.physical

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun Uuid.pctHex(): String = toHexString().replace("-", "").lowercase()

@OptIn(ExperimentalUuidApi::class)
fun parsePctUuid(raw: String): Uuid? {
    val hex = raw.trim().lowercase().replace("-", "")
    if (hex.length != 32 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val withDashes = buildString(36) {
        hex.forEachIndexed { index, char ->
            append(char)
            if (index == 7 || index == 11 || index == 15 || index == 19) append('-')
        }
    }
    return runCatching { Uuid.parse(withDashes) }.getOrNull()
}
