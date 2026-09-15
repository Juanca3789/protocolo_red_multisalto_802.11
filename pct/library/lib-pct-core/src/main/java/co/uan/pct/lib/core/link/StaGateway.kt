package co.uan.pct.lib.core.link

import android.net.ConnectivityManager
import android.net.Network
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Dirección del GO padre a la que abrir el TCP de control.
 *
 * No sirve `192.168.49.1`: todo GO de Android es `.1`, así que en cuanto este nodo levanta su
 * propio grupo, `192.168.49.1` pasa a ser una dirección local suya y los paquetes al padre se
 * entregan en `lo`. La `fe80::` del padre no choca: lleva el ámbito de la interfaz STA y Android
 * la forma por EUI-64 desde la MAC de la interfaz de grupo, que es el BSSID que ve la STA.
 */
fun staGateway(connectivity: ConnectivityManager, network: Network, bssid: String?): InetAddress {
    val props = connectivity.getLinkProperties(network)
    val linkLocal = bssid?.let { eui64LinkLocal(it) }
    if (linkLocal != null) {
        val iface = props?.interfaceName
            ?.let { name -> runCatching { NetworkInterface.getByName(name) }.getOrNull() }
        return if (iface != null) {
            Inet6Address.getByAddress(null, linkLocal, iface.index)
        } else {
            InetAddress.getByAddress(null, linkLocal)
        }
    }
    val gateway = props?.routes
        ?.firstOrNull { it.isDefaultRoute && it.hasGateway() }
        ?.gateway
    if (gateway != null) return gateway
    return InetAddress.getByName("192.168.49.1")
}

/** `fe80::` EUI-64 a partir de una MAC `aa:bb:cc:dd:ee:ff`; null si la MAC no es válida. */
internal fun eui64LinkLocal(mac: String): ByteArray? {
    val octets = mac.trim().split(':', '-')
    if (octets.size != 6) return null
    val m = octets.map { it.toIntOrNull(16) ?: return null }
    return byteArrayOf(
        0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0,
        (m[0] xor 0x02).toByte(), m[1].toByte(), m[2].toByte(),
        0xff.toByte(), 0xfe.toByte(),
        m[3].toByte(), m[4].toByte(), m[5].toByte(),
    )
}
