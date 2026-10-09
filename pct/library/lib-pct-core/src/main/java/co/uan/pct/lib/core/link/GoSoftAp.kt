package co.uan.pct.lib.core.link

import android.net.ConnectivityManager
import android.net.LinkAddress
import java.net.Inet4Address

/** IPv4 del nodo en su SoftAP/GO (respuesta ANNOUNCE_OK hacia hijos). */
object GoSoftAp {
    fun hostIpv4ForAnnounceOk(connectivity: ConnectivityManager): String? {
        var fallback: String? = null
        for (network in connectivity.allNetworks) {
            val props = connectivity.getLinkProperties(network) ?: continue
            for (la in props.linkAddresses) {
                val addr = la.address
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                if (host.startsWith("192.168.49.") && host.endsWith(".1")) return host
                if (host.startsWith("192.168.49.")) fallback = host
            }
        }
        if (fallback != null) return fallback
        for (network in connectivity.allNetworks) {
            val props = connectivity.getLinkProperties(network) ?: continue
            for (la in props.linkAddresses) {
                val addr = la.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }
}
