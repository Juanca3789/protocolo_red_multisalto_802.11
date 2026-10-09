package co.uan.pct.lib.core.link

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Direcciones IPv4 deducidas del [Network] que devuelve [co.uan.pct.lib.core.radio.sta.StaAssociation]
 * tras asociarse al GO del padre (no P2P genérico).
 */
object StaParentNetwork {
    /**
     * IP del GO padre en la ruta por defecto de esa STA — destino del `connect` TCP :8765
     * (`network.bindSocket` obligatorio en el hijo).
     */
    fun gatewayForControlTcp(
        connectivity: ConnectivityManager,
        staNetwork: Network,
    ): InetAddress? {
        val props = connectivity.getLinkProperties(staNetwork) ?: return null
        props.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.let { return it }
        return props.routes.firstOrNull { it.gateway != null }?.gateway
    }

    /**
     * Nuestra IPv4 en la subred del GO padre; es la que el hijo manda en el frame ANNOUNCE.
     */
    fun hostIpv4ForAnnounce(
        connectivity: ConnectivityManager,
        staNetwork: Network,
    ): String? {
        val props = connectivity.getLinkProperties(staNetwork) ?: return null
        return props.linkAddresses.firstStaIpv4Host()
    }

    private fun List<LinkAddress>.firstStaIpv4Host(): String? {
        for (la in this) {
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                return addr.hostAddress
            }
        }
        return null
    }
}
