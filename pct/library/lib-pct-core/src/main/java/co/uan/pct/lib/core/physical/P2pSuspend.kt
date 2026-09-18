package co.uan.pct.lib.core.physical

import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

class P2pFailureException(val reason: Int) : Exception("WifiP2p reason=$reason")

data class GoCredentials(
    val ssid: String,
    val psk: String,
)

suspend fun WifiP2pManager.awaitP2pAction(
    channel: WifiP2pManager.Channel,
    busyRetries: Int = 5,
    busyRetryMs: Long = 400L,
    call: (WifiP2pManager.Channel, WifiP2pManager.ActionListener) -> Unit,
) {
    var last: P2pFailureException? = null
    repeat(busyRetries.coerceAtLeast(1)) { attempt ->
        try {
            awaitP2pActionOnce(channel, call)
            return
        } catch (e: P2pFailureException) {
            if (e.reason != WifiP2pManager.BUSY) throw e
            last = e
            if (attempt < busyRetries - 1) delay(busyRetryMs)
        }
    }
    throw last ?: P2pFailureException(WifiP2pManager.BUSY)
}

private suspend fun WifiP2pManager.awaitP2pActionOnce(
    channel: WifiP2pManager.Channel,
    call: (WifiP2pManager.Channel, WifiP2pManager.ActionListener) -> Unit,
) {
    suspendCancellableCoroutine { cont ->
        call(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onFailure(reason: Int) {
                    if (cont.isActive) cont.resumeWithException(P2pFailureException(reason))
                }
            },
        )
    }
}

suspend fun WifiP2pManager.awaitStopPeerDiscovery(channel: WifiP2pManager.Channel) {
    runCatching { awaitP2pAction(channel) { ch, listener -> stopPeerDiscovery(ch, listener) } }
}

suspend fun WifiP2pManager.peekGroupInfo(channel: WifiP2pManager.Channel): WifiP2pGroup? =
    suspendCancellableCoroutine { cont ->
        requestGroupInfo(channel) { group ->
            if (cont.isActive) cont.resume(group)
        }
    }

suspend fun WifiP2pManager.awaitGroupInfo(
    channel: WifiP2pManager.Channel,
    maxAttempts: Int = 8,
    delayMs: Long = 500L,
    requireGroupOwner: Boolean = true,
): WifiP2pGroup {
    repeat(maxAttempts) { attempt ->
        val group = suspendCancellableCoroutine { cont ->
            requestGroupInfo(channel) { cont.resume(it) }
        }
        if (group != null && (!requireGroupOwner || group.isGroupOwner)) {
            return group
        }
        if (attempt < maxAttempts - 1) delay(delayMs)
    }
    throw IllegalStateException("requestGroupInfo sin grupo tras $maxAttempts intentos")
}

fun WifiP2pGroup.toGoCredentials(): GoCredentials = GoCredentials(
    ssid = networkName.orEmpty(),
    psk = passphrase.orEmpty(),
)

fun WifiP2pGroup.operatingMhz(): Int = runCatching {
    val getter = javaClass.methods.firstOrNull { it.name == "getFrequency" && it.parameterCount == 0 }
    (getter?.invoke(this) as? Int) ?: 0
}.getOrDefault(0)

fun Int.is24ghzSocial(): Boolean = this in 2412..2484
