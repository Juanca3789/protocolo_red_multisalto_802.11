package co.uan.pct.lib.core.link

import android.net.ConnectivityManager
import android.net.LinkAddress
import java.net.Inet4Address

/** IPv4 del nodo en su SoftAP/GO (respuesta ANNOUNCE_OK hacia hijos). */
object GoSoftAp {
    fun hostIpv4ForAnnounceOk(connectivity: ConnectivityManager): String? {
        for (network in connectivity.allNetworks) {
            val props = connectivity.getLinkProperties(network) ?: continue
            props.linkAddresses.firstGoIpv4()?.let { return it }
        }
        return null
    }

    private fun List<LinkAddress>.firstGoIpv4(): String? {
        for (la in this) {
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                val host = addr.hostAddress ?: continue
                if (host.startsWith("192.168.49.")) return host
            }
        }
        for (la in this) {
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                return addr.hostAddress
            }
        }
        return null
    }
}
