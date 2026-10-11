package co.uan.pct.lib.core.radio

import android.net.Network
import java.net.Inet6Address

/**
 * Red del GO propio lista para que Link escuche `:8765`.
 * L1 solo lo publica cuando la interfaz P2P ya tiene IPv4 (LK-01).
 * [network] puede ser null: hay OEM donde ConnectivityManager no expone la interfaz P2P
 * (LK-08); las direcciones salen de la interfaz del kernel y el bind es por dirección.
 */
data class GoControlEndpoint(
    val network: Network?,
    val iface: String,
    val hostIpv4: String,
    val linkLocalIpv6: Inet6Address?,
)
