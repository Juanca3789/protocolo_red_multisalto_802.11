package co.uan.pct.lib.core.radio.advertise

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import androidx.annotation.RequiresPermission
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.radio.android.P2pChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

class ControlDnsSd(
    private val p2p: P2pChannel,
) {
    private var listenersAttached = false
    private var activeServiceRequest: WifiP2pDnsSdServiceRequest? = null

    @Volatile
    private var currentResults: Channel<ControlAdvertisement>? = null

    private val scanByDevice = ConcurrentHashMap<String, ObservedCtrlService>()

    private val _lastScan = MutableStateFlow<List<ObservedCtrlService>>(emptyList())
    val lastScan: StateFlow<List<ObservedCtrlService>> = _lastScan.asStateFlow()

    private val onServiceListener =
        WifiP2pManager.DnsSdServiceResponseListener { instance, type, device ->
            onServiceFound(instance, type, device)
        }

    private val onTxtListener =
        WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
            onTxtRecord(record.orEmpty(), device)
        }

    @OptIn(ExperimentalUuidApi::class)
    @RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.NEARBY_WIFI_DEVICES])
    suspend fun browse(window: Duration): List<ControlAdvertisement> = coroutineScope {
        PctLog.event("browse: inicio ${window.inWholeSeconds}s (sin GO)")
        scanByDevice.clear()
        _lastScan.value = emptyList()

        val results = Channel<ControlAdvertisement>(capacity = Channel.UNLIMITED)
        currentResults = results

        try {
            p2p.runP2pAction {
                ensureListenersAttached()
                adapterCall("discoverPeers") { manager, wifiChannel, listener ->
                    manager.discoverPeers(wifiChannel, listener)
                }
                val request = WifiP2pDnsSdServiceRequest.newInstance(ControlAdvertisement.SERVICE_TYPE)
                activeServiceRequest = request
                adapterCall("addServiceRequest") { manager, wifiChannel, listener ->
                    manager.addServiceRequest(wifiChannel, request, listener)
                }
                adapterCall("discoverServices") { manager, wifiChannel, listener ->
                    manager.discoverServices(wifiChannel, listener)
                }
            }

            val byNodeId = linkedMapOf<String, ControlAdvertisement>()
            val collector = launch {
                results.consumeAsFlow().collect { ad ->
                    byNodeId[ad.nodeId.identifier.toString()] = ad
                }
            }

            val deadlineMs = System.currentTimeMillis() + window.inWholeMilliseconds
            var nextRediscoverMs = System.currentTimeMillis() + REDISCOVER_MS
            while (System.currentTimeMillis() < deadlineMs) {
                val now = System.currentTimeMillis()
                if (now >= nextRediscoverMs) {
                    p2p.runP2pAction {
                        adapterCall("discoverServices") { manager, wifiChannel, listener ->
                            manager.discoverServices(wifiChannel, listener)
                        }
                    }
                    nextRediscoverMs = now + REDISCOVER_MS
                }
                delay(POLL_MS)
            }

            results.close()
            collector.join()

            p2p.runP2pAction { stopBrowseOnAdapter() }

            PctLog.event("browse: fin candidatos=${byNodeId.size}")
            byNodeId.values.toList()
        } finally {
            currentResults = null
        }
    }

    @RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.NEARBY_WIFI_DEVICES])
    suspend fun publish(ad: ControlAdvertisement) {
        val wire = ad.toAndroidDnsSd()
        p2p.runP2pAction {
            ensureListenersAttached()
            val info = WifiP2pDnsSdServiceInfo.newInstance(
                wire.instanceName,
                wire.serviceType,
                wire.txtRecord,
            )
            adapterCall("addLocalService") { manager, wifiChannel, listener ->
                manager.addLocalService(wifiChannel, info, listener)
            }
        }
        PctLog.event("publish: instance=${wire.instanceName} role=${ad.role}")
    }

    @RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.NEARBY_WIFI_DEVICES])
    suspend fun unpublish() {
        p2p.runP2pAction {
            adapterCall("clearLocalServices") { manager, wifiChannel, listener ->
                manager.clearLocalServices(wifiChannel, listener)
            }
        }
    }

    private suspend fun P2pChannel.ensureListenersAttached() {
        if (listenersAttached) return
        manager.setDnsSdResponseListeners(wifiChannel, onServiceListener, onTxtListener)
        listenersAttached = true
    }

    private suspend fun P2pChannel.stopBrowseOnAdapter() {
        val request = activeServiceRequest
        activeServiceRequest = null
        if (request != null) {
            adapterCall("removeServiceRequest") { manager, wifiChannel, listener ->
                manager.removeServiceRequest(wifiChannel, request, listener)
            }
        }
        adapterCall("stopPeerDiscovery") { manager, wifiChannel, listener ->
            manager.stopPeerDiscovery(wifiChannel, listener)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun onServiceFound(instance: String?, type: String?, device: WifiP2pDevice?) {
        val address = device?.deviceAddress ?: return
        if (currentResults == null) return
        if (!ControlAdvertisement.isPctCtrlType(type)) return

        val name = instance.orEmpty().trim()
        PctLog.event("browse: servicio encontrado instance=$name mac=$address")

        val ad = ControlAdvertisement.fromInstance(name, address)
        if (ad != null) {
            emitCandidate(ad, name, address, device)
            PctLog.event("browse: usable instance-v1 ssid=${ad.groupSsid} mac=$address")
        } else {
            PctLog.event("browse: instance sin credenciales (espera TXT o re-anuncio host con prefijo p)")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun onTxtRecord(record: Map<String, String>, device: WifiP2pDevice?) {
        val address = device?.deviceAddress ?: return
        if (currentResults == null || record.isEmpty()) return

        val existing = scanByDevice[address]?.service
        val service = AndroidDnsSdService(
            instanceName = existing?.instanceName ?: "",
            serviceType = ControlAdvertisement.SERVICE_TYPE,
            txtRecord = record,
        )
        val ad = ControlAdvertisement.fromAndroidDnsSd(service) ?: return
        val instance = existing?.instanceName?.takeIf { it.isNotBlank() } ?: "txt-only"
        PctLog.event("browse: txt ok mac=$address ssid=${ad.groupSsid}")
        emitCandidate(ad, instance, address, device)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun emitCandidate(
        ad: ControlAdvertisement,
        instanceName: String,
        deviceAddress: String,
        device: WifiP2pDevice?,
    ) {
        val service = AndroidDnsSdService(
            instanceName = instanceName,
            serviceType = ControlAdvertisement.SERVICE_TYPE,
            txtRecord = mapOf(
                "n" to ad.nodeId.identifier.toHexString().replace("-", "").lowercase(),
                "s" to ad.groupSsid,
                "p" to ad.groupPassphrase,
            ),
        )
        currentResults?.trySend(ad)
        scanByDevice[deviceAddress] = ObservedCtrlService(
            service = service,
            deviceAddress = deviceAddress,
            rssiDbm = readRssiDbm(device),
        )
        _lastScan.value = scanByDevice.values.sortedBy { it.deviceAddress }
    }

    private fun readRssiDbm(device: WifiP2pDevice?): Int = runCatching {
        if (device == null) return 0
        val method = device.javaClass.methods.firstOrNull { it.name == "getRssi" && it.parameterCount == 0 }
        (method?.invoke(device) as? Int) ?: 0
    }.getOrDefault(0)

    private companion object {
        private const val POLL_MS = 200L
        private const val REDISCOVER_MS = 5_000L
    }
}
