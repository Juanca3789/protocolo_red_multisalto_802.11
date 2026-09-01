package co.uan.pct.lib.core.internal.util

import android.net.ConnectivityManager
import android.net.Network
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress

internal object PctNetworkHelper {
    const val DEFAULT_P2P_GW = "192.168.49.1"

    fun remoteIp(socket: Socket): String {
        return normalizeIp(socket.remoteSocketAddress)
    }

    fun normalizeIp(address: SocketAddress?): String {
        if (address !is InetSocketAddress) return ""
        val host = address.address?.hostAddress ?: address.hostString
        return normalizeHost(host)
    }

    fun normalizeHost(host: String?): String {
        if (host.isNullOrBlank()) return ""
        val stripped = host.substringBefore('%').trim()
        if (stripped.startsWith("::ffff:")) {
            return stripped.removePrefix("::ffff:")
        }
        return stripped
    }

    fun gatewayIp(cm: ConnectivityManager, network: Network?): String? {
        if (network == null) return null
        val routes = cm.getLinkProperties(network)?.routes.orEmpty()
        for (route in routes) {
            val gw = route.gateway ?: continue
            if (gw is Inet4Address && !gw.isAnyLocalAddress) {
                return normalizeHost(gw.hostAddress)
            }
        }
        return null
    }

    fun localIpOnNetwork(cm: ConnectivityManager, network: Network?): String? {
        if (network == null) return null
        for (addr in cm.getLinkProperties(network)?.linkAddresses.orEmpty()) {
            val a = addr.address ?: continue
            if (a is Inet4Address && !a.isLoopbackAddress) {
                return normalizeHost(a.hostAddress)
            }
        }
        return null
    }

    /** Android [Network.bindSocket] no acepta [ServerSocket]; escucha en 0.0.0.0. */
    @Suppress("UNUSED_PARAMETER")
    fun bindServer(server: ServerSocket, network: Network?) = Unit
}
