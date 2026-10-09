package co.uan.pct.lib.core.radio.android

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import androidx.annotation.RequiresPermission
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.Duration.Companion.milliseconds

private typealias P2pWork = suspend P2pChannel.() -> Unit

class P2pChannel(
    appContext: Context,
    val manager: WifiP2pManager,
    private val log: (priority: Int, message: String) -> Unit = Companion::emitLog,
) {
    private val thread = HandlerThread("multihop-p2p").apply { start() }

    val wifiChannel: WifiP2pManager.Channel =
        manager.initialize(appContext, thread.looper, null)

    private val p2pDispatcher = Handler(thread.looper).asCoroutineDispatcher("multihop-p2p")
    private val actions = Channel<P2pWork>(capacity = Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + p2pDispatcher)
    private val initialCleanupDone = CompletableDeferred<Unit>()

    init {
        log(Log.VERBOSE, "p2p channel initialized on thread ${thread.name}")
        scope.launch {
            actions.consumeAsFlow().collect { work ->
                work(this@P2pChannel)
            }
        }
        scope.launch {
            scheduleInitialCleanup()
        }
    }

    /**
     * Runs [block] on the P2P thread after prior queued work finishes.
     * Do not call from inside another [runP2pAction] (deadlock).
     */
    @RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.NEARBY_WIFI_DEVICES])
    suspend fun <T> runP2pAction(block: suspend P2pChannel.() -> T): T {
        initialCleanupDone.await()
        val result = Channel<Result<T>>(capacity = 1)
        actions.send {
            result.send(runCatching { block() })
        }
        return result.receive().getOrThrow()
    }

    /**
     * One adapter call (with BUSY retries). Only call from work running under [actions] collect.
     */
    @RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.NEARBY_WIFI_DEVICES])
    suspend fun adapterCall(
        label: String,
        call: (WifiP2pManager, WifiP2pManager.Channel, WifiP2pManager.ActionListener) -> Unit,
    ) {
        runAdapterCall(label = label, call = call, lenient = false)
    }

    /** Not queued: [WifiP2pManager.requestGroupInfo] is not an [WifiP2pManager.ActionListener] call. */
    @RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.NEARBY_WIFI_DEVICES])
    suspend fun readGroupInfo(): WifiP2pGroup? = suspendCancellableCoroutine { cont ->
        manager.requestGroupInfo(wifiChannel) { group ->
            if (cont.isActive) cont.resume(group)
        }
    }

    fun close() {
        log(Log.DEBUG, "p2p channel closing")
        actions.close()
        scope.cancel()
        thread.quitSafely()
    }

    private suspend fun scheduleInitialCleanup() {
        log(Log.VERBOSE, "p2p cleanup: scheduling ${INITIAL_CLEANUP_STEPS.size} steps")
        for ((name, step) in INITIAL_CLEANUP_STEPS) {
            actions.send {
                runCatching {
                    runAdapterCall(label = "cleanup:$name", call = step, lenient = true)
                }.onFailure { t ->
                    log(Log.WARN, "p2p cleanup: $name unexpected error: ${t.message}")
                }
            }
        }
        actions.send {
            log(Log.VERBOSE, "p2p cleanup: finished")
            initialCleanupDone.complete(Unit)
        }
    }

    /**
     * Finishes (success, lenient give-up, or throw) before returning — next collect item runs after.
     */
    private suspend fun runAdapterCall(
        label: String,
        call: (WifiP2pManager, WifiP2pManager.Channel, WifiP2pManager.ActionListener) -> Unit,
        lenient: Boolean,
        busyRetries: Int = 5,
        busyDelayMs: Long = 400L,
    ) {
        for (attempt in 0 until busyRetries.coerceAtLeast(1)) {
            val failureReason = suspendCancellableCoroutine { cont ->
                call(
                    manager,
                    wifiChannel,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onFailure(reason: Int) {
                            if (cont.isActive) cont.resume(reason)
                        }
                    },
                )
            }
            if (failureReason == null) {
                log(Log.VERBOSE, "p2p adapter ok label=$label attempt=${attempt + 1}")
                return
            }
            if (failureReason == WifiP2pManager.BUSY && attempt < busyRetries - 1) {
                log(
                    Log.VERBOSE,
                    "p2p adapter BUSY label=$label attempt=${attempt + 1}/$busyRetries retry in ${busyDelayMs}ms",
                )
                delay(busyDelayMs.milliseconds)
                continue
            }
            if (lenient) {
                log(
                    Log.VERBOSE,
                    "p2p adapter lenient fail label=$label reason=$failureReason attempt=${attempt + 1}",
                )
                return
            }
            log(Log.ERROR, "p2p adapter fail label=$label reason=$failureReason")
            throw ActionFailed(failureReason)
        }
    }

    private companion object {
        private const val LOG_TAG = "P2pChannel"

        val INITIAL_CLEANUP_STEPS = listOf(
            "stopPeerDiscovery" to WifiP2pManager::stopPeerDiscovery,
            "clearServiceRequests" to WifiP2pManager::clearServiceRequests,
            "clearLocalServices" to WifiP2pManager::clearLocalServices,
            "removeGroup" to WifiP2pManager::removeGroup,
        )

        private fun emitLog(priority: Int, message: String) {
            if (Log.isLoggable(LOG_TAG, priority)) {
                Log.println(priority, LOG_TAG, message)
            }
        }
    }

    class ActionFailed(val reason: Int) : Exception("WifiP2p action failed, reason=$reason")
}
