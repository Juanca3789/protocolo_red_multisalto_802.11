package co.uan.pct.lib.core.physical

import java.util.Base64
import kotlin.uuid.ExperimentalUuidApi

const val DNS_SD_CTRL_SERVICE_TYPE = "_pct-ctrl._tcp"

@OptIn(ExperimentalUuidApi::class)
fun dnsSdInstanceName(nid: kotlin.uuid.Uuid): String = "pct-${nid.pctHex().take(8)}"

@OptIn(ExperimentalUuidApi::class)
fun encodeDnsSdInstance(service: ServiceStructure): String = dnsSdInstanceName(service.nid)

@OptIn(ExperimentalUuidApi::class)
fun decodeDnsSdInstance(instance: String, p2pDeviceAddress: String): ServiceStructure? {
    if (!instance.startsWith("p") || instance.startsWith("pct-")) return null
    val raw = runCatching {
        String(Base64.getUrlDecoder().decode(instance.substring(1)), Charsets.UTF_8)
    }.getOrNull() ?: return null
    val parts = raw.split('|')
    if (parts.size < 3) return null
    val nid8 = parts[0].trim().lowercase()
    val ssid = parts[1].trim()
    val psk = parts[2].trim()
    if (nid8.length != 8 || ssid.isBlank() || psk.isBlank()) return null
    val nid = parsePctUuid(nid8 + "0".repeat(24)) ?: return null
    return ServiceStructure(
        nid = nid,
        role = Role.ROOT,
        depth = 0,
        ctrlPort = 8765,
        goSsid = ssid,
        goPsk = psk,
        childCount = 0,
        p2pDeviceAddress = p2pDeviceAddress,
    )
}

@OptIn(ExperimentalUuidApi::class)
fun encodeDnsSdTxt(service: ServiceStructure): Map<String, String> = mapOf(
    "v" to service.v.toString(),
    "nid" to service.nid.pctHex(),
    "role" to service.role.name,
    "depth" to service.depth.toString(),
    "cp" to service.ctrlPort.toString(),
    "go_ssid" to service.goSsid,
    "go_psk" to service.goPsk,
    "child_count" to service.childCount.toString(),
)

@OptIn(ExperimentalUuidApi::class)
fun decodeDnsSdTxt(txt: Map<String, String>, p2pDeviceAddress: String): ServiceStructure? {
    val nidRaw = txt["nid"]?.trim().orEmpty().ifBlank { txt["n"]?.trim().orEmpty() }
    val nid = parsePctUuid(nidRaw) ?: return null
    val role = parseAdvertisedRole(txt["role"] ?: txt["r"]) ?: Role.ROOT
    val depth = txt["depth"]?.toIntOrNull()
        ?: txt["hop"]?.toIntOrNull()
        ?: txt["d"]?.toIntOrNull()
        ?: 0
    val ctrlPort = txt["cp"]?.toIntOrNull() ?: txt["c"]?.toIntOrNull() ?: 8765
    val goSsid = txt["go_ssid"]?.trim().orEmpty().ifBlank { txt["s"]?.trim().orEmpty() }
    val goPsk = txt["go_psk"]?.trim().orEmpty().ifBlank { txt["p"]?.trim().orEmpty() }
    if (goSsid.isBlank() || goPsk.isBlank()) return null
    val childCount = txt["child_count"]?.toIntOrNull() ?: 0
    val v = txt["v"]?.toIntOrNull() ?: 1
    return ServiceStructure(
        nid = nid,
        role = role,
        depth = depth,
        ctrlPort = ctrlPort,
        goSsid = goSsid,
        goPsk = goPsk,
        childCount = childCount,
        p2pDeviceAddress = p2pDeviceAddress,
        v = v,
    )
}

internal fun parseAdvertisedRole(raw: String?): Role? {
    val name = raw?.trim()?.uppercase() ?: return null
    return when (name) {
        Role.ROOT.name -> Role.ROOT
        Role.BRIDGE.name, Role.LEAF.name -> Role.BRIDGE
        Role.ISLAND.name -> Role.ROOT
        else -> null
    }
}
