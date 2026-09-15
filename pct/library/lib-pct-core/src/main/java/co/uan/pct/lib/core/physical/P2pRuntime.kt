package co.uan.pct.lib.core.physical

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class P2pRuntime(
    appContext: Context,
    val manager: WifiP2pManager,
) {
    private val thread = HandlerThread("pct-p2p").apply { start() }
    private val handler = Handler(thread.looper)
    val channel: WifiP2pManager.Channel =
        manager.initialize(appContext.applicationContext, thread.looper, null)
    private val mutex = Mutex()
    private val errors = CoroutineExceptionHandler { _, t ->
        Log.e("PctMesh", "p2p: ${t.message}", t)
    }
    /** Fuera del looper de callbacks: si corre ahí, esperar un ActionListener bloquea el hilo. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)

    suspend fun <T> withP2p(block: suspend () -> T): T = mutex.withLock { block() }

    fun close() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
}
