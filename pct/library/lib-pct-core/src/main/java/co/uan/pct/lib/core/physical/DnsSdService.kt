package co.uan.pct.lib.core.physical

import android.Manifest
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * DNS-SD sobre un único Channel: anuncio pasivo + escucha de TXT/instance.
 * Las llamadas al framework las serializa [PhysicalLayer] con [P2pRuntime.withP2p].
 */
internal class DnsSdService(
    private val wifiP2pManager: WifiP2pManager,
) {
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    var onTxtRecord: ((Map<String, String>, WifiP2pDevice) -> Unit)? = null
    var onServiceFound: ((instance: String, type: String, device: WifiP2pDevice) -> Unit)? = null

    fun hasServiceRequest(): Boolean = serviceRequest != null

    fun registerListeners(channel: WifiP2pManager.Channel) {
        wifiP2pManager.setDnsSdResponseListeners(
            channel,
            { instance, type, device ->
                Log.i(TAG, "service found instance=$instance type=$type dev=${device.deviceAddress}")
                onServiceFound?.invoke(instance, type, device)
            },
            { domain, record, device ->
                Log.i(TAG, "TXT domain=$domain keys=${record?.keys} from ${device.deviceAddress}")
                onTxtRecord?.invoke(toValidTxtRecord(record.orEmpty()), device)
            },
        )
        wifiP2pManager.setServiceResponseListener(channel) { protocol, data, device ->
            val raw = data ?: ByteArray(0)
            Log.i(
                TAG,
                "SD proto=$protocol bytes=${raw.size} ascii=${raw.toString(Charsets.ISO_8859_1).take(160)} from ${device?.deviceAddress}",
            )
            val parsed = parseLenPrefixedTxt(raw)
            if (parsed.isNotEmpty()) {
                onTxtRecord?.invoke(toValidTxtRecord(parsed), device ?: WifiP2pDevice())
            }
        }
    }

    fun clearServiceRequests(
        channel: WifiP2pManager.Channel,
        listener: WifiP2pManager.ActionListener?,
    ) {
        serviceRequest = null
        wifiP2pManager.clearServiceRequests(channel, listener)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun advertise(
        channel: WifiP2pManager.Channel,
        serviceName: String,
        serviceType: String,
        txtRecord: Map<String, String>,
        listener: WifiP2pManager.ActionListener,
    ) {
        registerListeners(channel)
        val info = WifiP2pDnsSdServiceInfo.newInstance(
            serviceName,
            serviceType,
            toValidTxtRecord(txtRecord),
        )
        wifiP2pManager.addLocalService(channel, info, listener)
    }

    fun clearLocalServices(
        channel: WifiP2pManager.Channel,
        listener: WifiP2pManager.ActionListener?,
    ) {
        wifiP2pManager.clearLocalServices(channel, listener)
    }

    fun addServiceRequest(
        channel: WifiP2pManager.Channel,
        serviceType: String,
        listener: WifiP2pManager.ActionListener,
    ) {
        registerListeners(channel)
        // Sin filtro de tipo: el sample AOSP y Samsung entregan el TXT Bonjour
        // solo si el request coincide con lo que responde el peer. El tipo
        // `_pct-ctrl._tcp` se sigue anunciando; el request pide cualquier TXT.
        val request = WifiP2pDnsSdServiceRequest.newInstance()
        serviceRequest = request
        wifiP2pManager.addServiceRequest(channel, request, listener)
        Log.i(TAG, "addServiceRequest Bonjour (anuncio=$serviceType)")
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun discoverServices(
        channel: WifiP2pManager.Channel,
        listener: WifiP2pManager.ActionListener,
    ) {
        wifiP2pManager.discoverServices(channel, listener)
    }

    fun stopDiscovery(
        channel: WifiP2pManager.Channel,
        listener: WifiP2pManager.ActionListener?,
    ) {
        val request = serviceRequest
        serviceRequest = null
        if (request != null) {
            wifiP2pManager.removeServiceRequest(channel, request, listener)
        } else {
            listener?.onSuccess()
        }
    }

    private fun toValidTxtRecord(raw: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((key, value) in raw) {
            val k = key.trim()
            if (k.isEmpty()) continue
            out[k] = truncateUtf8(value.trim(), MAX_TXT_VALUE_BYTES)
        }
        return out
    }

    private fun parseLenPrefixedTxt(data: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var i = 0
        while (i < data.size) {
            val len = data[i].toInt() and 0xff
            if (len == 0) {
                i++
                continue
            }
            if (i + 1 + len > data.size) break
            val pair = runCatching { String(data, i + 1, len, Charsets.UTF_8) }.getOrNull().orEmpty()
            val eq = pair.indexOf('=')
            if (eq > 0) out[pair.substring(0, eq)] = pair.substring(eq + 1)
            i += 1 + len
        }
        if (out.isEmpty()) {
            val ascii = runCatching { String(data, Charsets.ISO_8859_1) }.getOrNull().orEmpty()
            for (key in listOf("go_ssid", "go_psk", "nid", "role", "depth", "cp", "n", "s", "p")) {
                val token = "$key="
                val at = ascii.indexOf(token)
                if (at < 0) continue
                val start = at + token.length
                var end = start
                while (end < ascii.length && ascii[end] >= ' ' && ascii[end] != '\u0000') end++
                val value = ascii.substring(start, end).trim()
                if (value.isNotEmpty()) out[key] = value
            }
        }
        return out
    }

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.isEmpty()) return text
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var end = maxBytes
        while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "PctMesh"
        const val MAX_TXT_VALUE_BYTES = 255
    }
}
