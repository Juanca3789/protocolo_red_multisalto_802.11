package co.uan.pct.lib.core.radio.sta

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import androidx.annotation.RequiresPermission
import co.uan.pct.lib.core.radio.advertise.AndroidDnsSdService
import co.uan.pct.lib.core.radio.advertise.ControlAdvertisement
import co.uan.pct.lib.core.radio.advertise.ObservedCtrlService
import co.uan.pct.lib.core.types.Role
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class StaAssociation(
    context: Context,
    parentMacState: MutableStateFlow<String?>,
    private val staNetworkState: MutableStateFlow<Network?>,
    private val log: (priority: Int, message: String) -> Unit = Companion::emitLog,
) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val parentMac: StateFlow<String?> = parentMacState.asStateFlow()
    private val parentMacOut = parentMacState

    @RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.NEARBY_WIFI_DEVICES])
    suspend fun connect(service: AndroidDnsSdService, parentDeviceAddress: String): Network {
        val parsed = ControlAdvertisement.fromAndroidDnsSd(service)
            ?: throw IllegalArgumentException("invalid _pct-ctrl service for STA")
        log(
            Log.DEBUG,
            "sta: connect ssid=${parsed.groupSsid} pskLen=${parsed.groupPassphrase.length}",
        )
        disconnect()
        val mac = parentDeviceAddress.trim().takeIf { it.isNotBlank() }
        return suspendCancellableCoroutine { cont ->
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(parsed.groupSsid)
                .setWpa2Passphrase(parsed.groupPassphrase)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networkCallback = this
                    if (cont.isActive) cont.resume(network)
                }

                override fun onUnavailable() {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("STA unavailable"))
                    }
                }
            }
            cont.invokeOnCancellation {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
            connectivity.requestNetwork(request, callback)
        }.also { network ->
            parentMacOut.value = mac
            staNetworkState.value = network
            log(Log.DEBUG, "sta: connected network=$network parentMac=$mac")
        }
    }

    fun disconnect() {
        networkCallback?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
                .onFailure { t -> log(Log.WARN, "sta: disconnect ${t.message}") }
        }
        networkCallback = null
        parentMacOut.value = null
        staNetworkState.value = null
        log(Log.DEBUG, "sta: disconnected")
    }

    companion object {
        private const val LOG_TAG = "StaAssociation"

        /**
         * Simple TOPSIS (min criteria): child_count, role rank, optional RSSI cost (-dBm).
         */
        @OptIn(ExperimentalUuidApi::class)
        fun selectParent(
            observed: List<ObservedCtrlService>,
            excludeNodeId: Uuid? = null,
            log: (priority: Int, message: String) -> Unit = ::emitLog,
        ): SelectedParent? {
            val alternatives = observed.mapNotNull { obs ->
                val ad = ControlAdvertisement.fromAndroidDnsSd(obs.service) ?: return@mapNotNull null
                if (excludeNodeId != null && ad.nodeId.identifier == excludeNodeId) {
                    return@mapNotNull null
                }
                if (ad.role == Role.ISLAND) {
                    return@mapNotNull null
                }
                ParentAlternative(
                    service = obs.service,
                    deviceAddress = obs.deviceAddress,
                    childCount = ad.childCount,
                    roleCost = roleCost(ad.role),
                    rssiDbm = obs.rssiDbm,
                )
            }
            if (alternatives.isEmpty()) {
                log(Log.DEBUG, "sta topsis: no alternatives")
                return null
            }
            if (alternatives.size == 1) {
                log(Log.DEBUG, "sta topsis: single alternative")
                val only = alternatives.first()
                return SelectedParent(only.service, only.deviceAddress)
            }
            val useRssi = alternatives.any { it.rssiDbm != 0 }
            val picked = topsisMin(alternatives, useRssi)
            log(
                Log.DEBUG,
                "sta topsis: picked instance=${picked.service.instanceName} mac=${picked.deviceAddress} " +
                    "children=${picked.childCount} roleCost=${picked.roleCost} rssi=${picked.rssiDbm}",
            )
            return SelectedParent(picked.service, picked.deviceAddress)
        }

        data class SelectedParent(
            val service: AndroidDnsSdService,
            val deviceAddress: String,
        )

        private fun roleCost(role: Role): Int = when (role) {
            Role.ROOT -> 1
            Role.BRIDGE -> 2
            Role.LEAF -> 3
            Role.ISLAND -> 4
        }

        private data class ParentAlternative(
            val service: AndroidDnsSdService,
            val deviceAddress: String,
            val childCount: Int,
            val roleCost: Int,
            val rssiDbm: Int,
        )

        private fun topsisMin(alts: List<ParentAlternative>, useRssi: Boolean): ParentAlternative {
            val n = alts.size
            val matrix = Array(n) { i ->
                val a = alts[i]
                if (useRssi) {
                    doubleArrayOf(
                        a.childCount.toDouble(),
                        a.roleCost.toDouble(),
                        rssiCost(a.rssiDbm).toDouble(),
                    )
                } else {
                    doubleArrayOf(a.childCount.toDouble(), a.roleCost.toDouble())
                }
            }
            val m = matrix[0].size
            val weights = DoubleArray(m) { 1.0 / m }
            val colNorm = DoubleArray(m)
            for (j in 0 until m) {
                var sumSq = 0.0
                for (i in 0 until n) {
                    sumSq += matrix[i][j] * matrix[i][j]
                }
                colNorm[j] = sqrt(sumSq).takeIf { it > 0.0 } ?: 1.0
            }
            val weighted = Array(n) { i ->
                DoubleArray(m) { j ->
                    (matrix[i][j] / colNorm[j]) * weights[j]
                }
            }
            val ideal = DoubleArray(m) { j ->
                (0 until n).minOf { i -> weighted[i][j] }
            }
            val anti = DoubleArray(m) { j ->
                (0 until n).maxOf { i -> weighted[i][j] }
            }
            var bestIdx = 0
            var bestScore = -1.0
            for (i in 0 until n) {
                var dIdeal = 0.0
                var dAnti = 0.0
                for (j in 0 until m) {
                    dIdeal += (weighted[i][j] - ideal[j]).let { it * it }
                    dAnti += (weighted[i][j] - anti[j]).let { it * it }
                }
                dIdeal = sqrt(dIdeal)
                dAnti = sqrt(dAnti)
                val score = if (dIdeal + dAnti == 0.0) 0.0 else dAnti / (dIdeal + dAnti)
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }
            return alts[bestIdx]
        }

        /** Lower is better: stronger signal → smaller cost. */
        private fun rssiCost(rssiDbm: Int): Int = when {
            rssiDbm == 0 -> 0
            rssiDbm > 0 -> -rssiDbm
            else -> -rssiDbm
        }

        private fun emitLog(priority: Int, message: String) {
            if (Log.isLoggable(LOG_TAG, priority)) {
                Log.println(priority, LOG_TAG, message)
            }
        }
    }
}
