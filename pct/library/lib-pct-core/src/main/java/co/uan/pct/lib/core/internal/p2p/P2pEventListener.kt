package co.uan.pct.lib.core.internal.p2p

import android.content.Intent

fun interface P2pEventListener {
    fun onP2pIntent(intent: Intent)
}
