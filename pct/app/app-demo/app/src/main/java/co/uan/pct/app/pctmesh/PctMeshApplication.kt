package co.uan.pct.app.pctmesh

import android.app.Activity
import android.app.Application
import android.os.Bundle
import co.uan.pct.lib.core.PctCore
import co.uan.pct.lib.core.api.PctNode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mantiene **una** instancia de [PctNode] por proceso Android.
 *
 * - [acquireNode]: create + init si hace falta (rotación de pantalla, reentrada).
 * - [releaseNode]: close() para liberar Wi‑Fi Direct y no dejar grupo zombi.
 */
class PctMeshApplication : Application() {

    private val activityCount = AtomicInteger(0)
    private var nodeClosed = true

    lateinit var pctNode: PctNode
        private set

    override fun onCreate() {
        super.onCreate()
        acquireNode()
        registerActivityLifecycleCallbacks(activityCallbacks)
    }

    /** Garantiza un nodo usable (re-init tras close al destruir la UI). */
    @Synchronized
    fun acquireNode(): PctNode {
        if (!::pctNode.isInitialized || nodeClosed) {
            pctNode = PctCore.create()
            pctNode.init(this)
            nodeClosed = false
        }
        return pctNode
    }

    /** Detiene P2P/GO/DNS/STA para no dejar el stack zombi. */
    @Synchronized
    fun releaseNode() {
        if (::pctNode.isInitialized && !nodeClosed) {
            runCatching { pctNode.close() }
            nodeClosed = true
        }
    }

    override fun onTerminate() {
        releaseNode()
        super.onTerminate()
    }

    private val activityCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activityCount.incrementAndGet()
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            val left = activityCount.decrementAndGet()
            // Última Activity destruida y saliendo (no solo rotación de config)
            if (left <= 0 && activity.isFinishing) {
                releaseNode()
            }
        }
    }
}
