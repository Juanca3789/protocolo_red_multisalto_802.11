package co.uan.pct.lib.core.physical

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class StaUnavailableException(val ssid: String) :
    Exception("No se pudo asociar STA a $ssid")

/** Valor que Android devuelve como BSSID cuando el callback no pide ubicación. */
private const val REDACTED_BSSID = "02:00:00:00:00:00"

internal class StaLink(
    private val connectivityManager: ConnectivityManager,
) {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    var onLost: (() -> Unit)? = null

    /** BSSID del GO padre (MAC de su interfaz de grupo); de él sale su `fe80::` EUI-64. */
    @Volatile
    var bssid: String? = null
        private set

    suspend fun connect(ssid: String, psk: String): Network =
        suspendCancellableCoroutine { cont ->
            disconnect()
            val request = buildStaRequest(ssid, psk)
            val callback = object : ConnectivityManager.NetworkCallback(
                FLAG_INCLUDE_LOCATION_INFO,
            ) {
                override fun onAvailable(network: Network) {
                    connectivityManager.bindProcessToNetwork(network)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) {
                    (caps.transportInfo as? WifiInfo)?.bssid
                        ?.takeIf { it != REDACTED_BSSID }
                        ?.let { bssid = it }
                    if (cont.isActive) cont.resume(network)
                }

                override fun onUnavailable() {
                    if (cont.isActive) cont.resumeWithException(StaUnavailableException(ssid))
                }

                override fun onLost(network: Network) {
                    onLost?.invoke()
                }
            }
            networkCallback = callback
            cont.invokeOnCancellation { unregister(callback) }
            try {
                connectivityManager.requestNetwork(request, callback)
            } catch (e: SecurityException) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        SecurityException("CHANGE_NETWORK_STATE: ${e.message}", e),
                    )
                }
            }
        }

    fun disconnect() {
        networkCallback?.let { unregister(it) }
        networkCallback = null
        bssid = null
        connectivityManager.bindProcessToNetwork(null)
    }

    private fun unregister(callback: ConnectivityManager.NetworkCallback) {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun buildStaRequest(ssid: String, psk: String): NetworkRequest {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(psk)
            .build()
        return NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
    }
}
