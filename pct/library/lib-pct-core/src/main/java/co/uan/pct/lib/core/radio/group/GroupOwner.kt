package co.uan.pct.lib.core.radio.group

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.NEARBY_WIFI_DEVICES
import android.net.wifi.p2p.WifiP2pConfig
import android.util.Log
import androidx.annotation.RequiresPermission
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.radio.android.P2pChannel
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class GroupOwner(
    private val p2p: P2pChannel,
    private val log: (priority: Int, message: String) -> Unit = Companion::emitLog,
) {

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    suspend fun start(): GoCredentials {
        log(Log.VERBOSE, "go: start requested")
        // `DIRECT-xy…-PCT` (32 máx). PSK 8: el instance-v1 (`p`+b64) no pasa de 63.
        val ssid = "DIRECT-${randomToken(16)}-PCT"
        val psk = randomToken(8)
        val config = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(psk)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .build()
        p2p.runP2pAction {
            adapterCall(label = "createGroup") { manager, wifiChannel, listener ->
                manager.createGroup(wifiChannel, config, listener)
            }
        }
        return readOwnGoCredentials()
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    suspend fun stop() {
        log(Log.DEBUG, "go: stop requested")
        p2p.runP2pAction {
            adapterCall(label = "removeGroup") { manager, wifiChannel, listener ->
                manager.removeGroup(wifiChannel, listener)
            }
        }
        log(Log.DEBUG, "go: stop finished")
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    private suspend fun readOwnGoCredentials(): GoCredentials {
        repeat(GROUP_INFO_ATTEMPTS) { attempt ->
            val group = p2p.readGroupInfo()
            if (group == null) {
                log(
                    Log.WARN,
                    "go: requestGroupInfo null attempt=${attempt + 1}/$GROUP_INFO_ATTEMPTS",
                )
            } else if (!group.isGroupOwner) {
                log(
                    Log.WARN,
                    "go: not group owner yet attempt=${attempt + 1}/$GROUP_INFO_ATTEMPTS",
                )
            } else {
                val ssid = group.networkName.orEmpty().trim()
                val passphrase = group.passphrase.orEmpty().trim()
                if (ssid.isEmpty() || passphrase.isEmpty()) {
                    log(
                        Log.WARN,
                        "go: empty ssid/psk attempt=${attempt + 1}/$GROUP_INFO_ATTEMPTS " +
                            "ssidLen=${ssid.length} pskLen=${passphrase.length}",
                    )
                } else {
                    PctLog.event("go: credentials ok ssid=$ssid freq=${group.frequency}MHz attempt=${attempt + 1}")
                    return GoCredentials(ssid, passphrase)
                }
            }
            if (attempt < GROUP_INFO_ATTEMPTS - 1) {
                delay(GROUP_INFO_DELAY_MS.milliseconds)
            }
        }
        PctLog.event("go: credentials unavailable after $GROUP_INFO_ATTEMPTS attempts")
        log(Log.ERROR, "go: credentials unavailable after $GROUP_INFO_ATTEMPTS attempts")
        throw IllegalStateException("Group owner credentials not available after $GROUP_INFO_ATTEMPTS attempts")
    }

    private companion object {
        private const val LOG_TAG = "GroupOwner"
        const val GROUP_INFO_ATTEMPTS = 8
        const val GROUP_INFO_DELAY_MS = 500L
        private val TOKEN_ALPHABET = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        private val random = SecureRandom()

        /** Android exige `DIRECT-xy…` (dos alfanum) y PSK de 8..63. */
        private fun randomToken(length: Int): String = buildString(length) {
            repeat(length) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.size)]) }
        }

        private fun emitLog(priority: Int, message: String) {
            if (Log.isLoggable(LOG_TAG, priority)) {
                Log.println(priority, LOG_TAG, message)
            }
        }
    }
}
