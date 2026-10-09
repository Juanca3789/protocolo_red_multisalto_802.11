package co.uan.pct.lib.core.logging

import android.util.Log

/** Logcat del módulo. Eventos de sesión en [event] (tag fijo [EVENT_TAG]). */
object PctLog {
    const val EVENT_TAG = "PctMesh"

    @Volatile
    var minPriority: Int = Log.INFO

    fun event(message: String) {
        Log.i(EVENT_TAG, message)
    }

    fun emit(tag: String, priority: Int, message: String) {
        if (priority >= minPriority && Log.isLoggable(tag, priority)) {
            Log.println(priority, tag, message)
        }
    }

    fun logger(tag: String): (priority: Int, message: String) -> Unit = { priority, message ->
        emit(tag, priority, message)
    }
}
