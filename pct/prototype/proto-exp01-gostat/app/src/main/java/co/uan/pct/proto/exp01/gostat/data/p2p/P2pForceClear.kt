package co.uan.pct.proto.exp01.gostat.data.p2p

import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

/**
 * Libera la cola de [WifiP2pManager] (una acción a la vez). Cada paso sigue
 * aunque falle: el objetivo es matar ocupantes, no quedar colgado en BUSY.
 */
class P2pForceClear(
    private val p2p: P2pChannelHolder,
) {
    private val handler = Handler(Looper.getMainLooper())

    fun free(
        killGroup: Boolean,
        killLocalServices: Boolean,
        then: () -> Unit,
    ) {
        p2p.manager.stopPeerDiscovery(p2p.channel, anyway {
            p2p.manager.cancelConnect(p2p.channel, anyway {
                p2p.manager.clearServiceRequests(p2p.channel, anyway {
                    if (killLocalServices) {
                        p2p.manager.clearLocalServices(p2p.channel, anyway {
                            maybeRemove(killGroup, then)
                        })
                    } else {
                        maybeRemove(killGroup, then)
                    }
                })
            })
        })
    }

    private fun maybeRemove(killGroup: Boolean, then: () -> Unit) {
        if (!killGroup) {
            settle(then)
            return
        }
        p2p.manager.removeGroup(p2p.channel, anyway { settle(then) })
    }

    private fun settle(then: () -> Unit) {
        handler.postDelayed(then, 250)
    }

    private fun anyway(next: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = next()
        override fun onFailure(reason: Int) = next()
    }
}
