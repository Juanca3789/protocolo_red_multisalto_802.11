package co.uan.pct.lib.core.radio.advertise

import co.uan.pct.lib.core.types.NodeId
import co.uan.pct.lib.core.types.Role
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class ControlAdvertisement(
    val nodeId: NodeId,
    val role: Role,
    val groupSsid: String,
    val groupPassphrase: String,
    val childCount: Int,
) {
    fun toAndroidDnsSd(): AndroidDnsSdService {
        val hex = nodeId.identifier.toHexString().replace("-", "").lowercase()
        require(hex.length == 32) { "node id must be 128 bits" }
        val instance = when (val enc = PctInstanceCodec.encode(hex, groupSsid.trim(), groupPassphrase.trim())) {
            is PctInstanceCodec.EncodeResult.Success -> enc.instanceName
            is PctInstanceCodec.EncodeResult.Failure -> error(enc.reason)
        }
        val txtRecord = mapOf(
            "n" to hex,
            "s" to groupSsid.trim(),
            "p" to groupPassphrase.trim(),
            "role" to role.name,
            "child_count" to childCount.coerceAtLeast(0).toString(),
        )
        return AndroidDnsSdService(
            instanceName = instance,
            serviceType = SERVICE_TYPE,
            txtRecord = txtRecord,
        )
    }

    companion object {
        const val SERVICE_TYPE = "_pct-ctrl._tcp"

        fun isPctCtrlType(registrationType: String?): Boolean =
            registrationType?.contains(SERVICE_TYPE, ignoreCase = true) == true

        fun normalizeServiceType(raw: String): String {
            var t = raw.trim().lowercase()
            while (t.endsWith('.')) t = t.dropLast(1)
            if (t.endsWith(".local")) {
                t = t.removeSuffix(".local")
                while (t.endsWith('.')) t = t.dropLast(1)
            }
            return t
        }

        /** Tras service found: credenciales desde instance-v1 (canal primario exp01). */
        fun fromInstance(
            instanceName: String,
            deviceAddress: String,
        ): ControlAdvertisement? {
            val payload = PctInstanceCodec.decode(instanceName) ?: return null
            val nidHex = payload.nidShort.padEnd(32, '0')
            val uuid = uuidFromHex32(nidHex) ?: return null
            return ControlAdvertisement(
                nodeId = NodeId(identifier = uuid, name = ""),
                role = Role.ROOT,
                groupSsid = payload.goSsid,
                groupPassphrase = payload.goPsk,
                childCount = 0,
            )
        }

        fun fromAndroidDnsSd(service: AndroidDnsSdService): ControlAdvertisement? {
            if (normalizeServiceType(service.serviceType) != SERVICE_TYPE) {
                return null
            }
            if (service.txtRecord.isEmpty()) {
                return fromInstance(service.instanceName, deviceAddress = "")
            }
            val txt = service.txtRecord
            val hex = pick(txt, "n", "nid")?.lowercase() ?: return null
            val ssid = pick(txt, "s", "ssid", "go_ssid") ?: return null
            val psk = pick(txt, "p", "psk", "go_psk") ?: return null
            val uuid = uuidFromHex32(hex.padEnd(32, '0').take(32)) ?: return null
            val role = pick(txt, "role", "r")?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: Role.ROOT
            val childCount = pick(txt, "child_count", "c")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            return ControlAdvertisement(
                nodeId = NodeId(identifier = uuid, name = ""),
                role = role,
                groupSsid = ssid,
                groupPassphrase = psk,
                childCount = childCount,
            )
        }

        private fun pick(map: Map<String, String>, vararg keys: String): String? {
            for (key in keys) {
                val v = map[key]?.trim()
                if (!v.isNullOrEmpty()) return v
            }
            return null
        }

        private fun uuidFromHex32(hex32: String): Uuid? {
            if (hex32.length != 32) return null
            val dashed = buildString(36) {
                hex32.forEachIndexed { index, c ->
                    append(c)
                    if (index == 7 || index == 11 || index == 15 || index == 19) append('-')
                }
            }
            return runCatching { Uuid.parse(dashed) }.getOrNull()
        }
    }
}
