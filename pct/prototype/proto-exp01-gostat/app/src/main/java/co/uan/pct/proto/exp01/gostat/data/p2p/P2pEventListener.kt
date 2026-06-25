package co.uan.pct.proto.exp01.gostat.data.p2p

import android.content.Intent

fun interface P2pEventListener {
    fun onP2pIntent(intent: Intent)
}
