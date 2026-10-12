package co.uan.pct.lib.core.link.network

import android.net.ConnectivityManager
import android.net.Network
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Direcciones deducidas del [Network] STA hacia el GO padre
 * ([co.uan.pct.lib.core.radio.sta.StaAssociation]).
 */
object StaParentNetwork {
    /**
     * `fe80::` del GO padre derivada del BSSID (EUI-64) con ámbito de la interfaz STA.
     * Es el destino principal del `connect` :8765: en un BRIDGE la IPv4 `.1` también es
     * local y el TCP caería a `lo` (PC-08, LK-05).
     */
    fun fe80GatewayForControlTcp(
        connectivity: ConnectivityManager,
        staNetwork: Network,
        parentBssid: String?,
    ): Inet6Address? {
        val mac = parseMac(parentBssid ?: return null) ?: return null
        val iface = connectivity.getLinkProperties(staNetwork)?.interfaceName ?: return null
        val nif = runCatching { NetworkInterface.getByName(iface) }.getOrNull() ?: return null
        val bytes = ByteArray(16)
        bytes[0] = 0xfe.toByte()
        bytes[1] = 0x80.toByte()
        bytes[8] = (mac[0].toInt() xor 0x02).toByte()
        bytes[9] = mac[1]
        bytes[10] = mac[2]
        bytes[11] = 0xff.toByte()
        bytes[12] = 0xfe.toByte()
        bytes[13] = mac[3]
        bytes[14] = mac[4]
        bytes[15] = mac[5]
        return runCatching { Inet6Address.getByAddress(null, bytes, nif) }.getOrNull()
    }

    private fun parseMac(text: String): ByteArray? {
        val parts = text.trim().split(':')
        if (parts.size != 6) return null
        val out = ByteArray(6)
        for (i in 0 until 6) {
            out[i] = parts[i].toIntOrNull(16)?.toByte() ?: return null
        }
        return out
    }

    /** Gateway IPv4 en esa STA; solo fallback cuando no hay BSSID (ver [fe80GatewayForControlTcp]). */
    fun gatewayForControlTcp(
        connectivity: ConnectivityManager,
        staNetwork: Network,
    ): InetAddress? {
        val props = connectivity.getLinkProperties(staNetwork) ?: return null
        props.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.let { return it }
        return props.routes.firstOrNull { it.gateway != null }?.gateway
    }

    /** Nuestra IPv4 en la STA hacia el padre → cuerpo ANNOUNCE (`ipv4OnLink`). */
    fun hostIpv4ForAnnounce(
        connectivity: ConnectivityManager,
        staNetwork: Network,
    ): String? {
        val props = connectivity.getLinkProperties(staNetwork) ?: return null
        for (la in props.linkAddresses) {
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                return addr.hostAddress
            }
        }
        return null
    }
}
