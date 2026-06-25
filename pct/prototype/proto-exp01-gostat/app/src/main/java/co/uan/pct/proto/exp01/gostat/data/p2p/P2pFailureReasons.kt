package co.uan.pct.proto.exp01.gostat.data.p2p

import android.net.wifi.p2p.WifiP2pManager

object P2pFailureReasons {

    fun describe(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR (0)"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED (1)"
        WifiP2pManager.BUSY -> "BUSY (2)"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS (3)"
        else -> "desconocido ($reason)"
    }
}
