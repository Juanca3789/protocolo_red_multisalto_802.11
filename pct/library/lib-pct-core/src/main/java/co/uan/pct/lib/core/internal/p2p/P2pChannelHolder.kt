package co.uan.pct.lib.core.internal.p2p

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build

class P2pChannelHolder(
    private val app: Application,
) {
    val manager: WifiP2pManager =
        app.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    val channel: WifiP2pManager.Channel by lazy {
        manager.initialize(app, app.mainLooper, null)
    }

    private val listeners = mutableSetOf<P2pEventListener>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            listeners.forEach { it.onP2pIntent(intent) }
        }
    }

    fun addListener(listener: P2pEventListener) {
        listeners += listener
    }

    fun removeListener(listener: P2pEventListener) {
        listeners -= listener
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { app.unregisterReceiver(receiver) }
        registered = false
    }

    /**
     * Apaga discovery, servicios locales y grupo GO residuales.
     * Seguro llamar al cerrar la app o al reabrir tras un proceso zombi.
     */
    fun forceTeardownP2p() {
        runCatching {
            manager.stopPeerDiscovery(channel, null)
        }
        runCatching {
            manager.clearLocalServices(channel, null)
        }
        runCatching {
            manager.clearServiceRequests(channel, null)
        }
        runCatching {
            manager.removeGroup(channel, null)
        }
    }
}
