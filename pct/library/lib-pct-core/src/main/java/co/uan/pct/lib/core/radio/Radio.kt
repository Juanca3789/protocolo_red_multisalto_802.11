package co.uan.pct.lib.core.radio

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.NEARBY_WIFI_DEVICES
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import androidx.annotation.RequiresPermission
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.radio.advertise.ControlAdvertisement
import co.uan.pct.lib.core.radio.advertise.ControlDnsSd
import co.uan.pct.lib.core.radio.android.P2pChannel
import co.uan.pct.lib.core.radio.group.GoCredentials
import co.uan.pct.lib.core.radio.group.GroupOwner
import co.uan.pct.lib.core.radio.sta.StaAssociation
import co.uan.pct.lib.core.types.BootStep
import co.uan.pct.lib.core.types.NodeId
import co.uan.pct.lib.core.types.Role
import co.uan.pct.lib.core.types.SessionStatus
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalUuidApi::class)
class Radio(
    context: Context,
    private val nodeId: NodeId,
    private val roleState: MutableStateFlow<Role>,
    private val connectedMacsState: MutableStateFlow<List<String>>,
    parentMacState: MutableStateFlow<String?>,
    staNetworkState: MutableStateFlow<Network?>,
    parentBssidState: MutableStateFlow<String?>,
    private val goEndpointState: MutableStateFlow<GoControlEndpoint?>,
) {
    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(ConnectivityManager::class.java)

    private val wifiP2pManager: WifiP2pManager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    private val p2pChannel = P2pChannel(appContext, wifiP2pManager, PctLog.logger("P2pChannel"))
    private val dnsSd = ControlDnsSd(p2pChannel)
    private val groupOwner = GroupOwner(p2pChannel, PctLog.logger("GroupOwner"))
    private val sta = StaAssociation(
        appContext,
        parentMacState,
        staNetworkState,
        parentBssidState,
        PctLog.logger("StaAssociation"),
    )

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var active = false

    val role: StateFlow<Role> = roleState.asStateFlow()
    val connectedMacs: StateFlow<List<String>> = connectedMacsState.asStateFlow()

    private val _bootStatus = MutableStateFlow<SessionStatus>(SessionStatus.Stopped)
    internal val bootStatus: StateFlow<SessionStatus> = _bootStatus.asStateFlow()

    private var goCredentials: GoCredentials? = null
    private var lastChildCount = 0
    private var lastPendingReason: String? = null
    private var topologyRole: Role = Role.ISLAND
    private var parentSsid: String? = null

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    fun start() {
        if (active) return
        active = true
        PctLog.event("session: start")
        scope.launch {
            try {
                runSession()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (_bootStatus.value !is SessionStatus.Failed) {
                    emitBoot(
                        SessionStatus.Failed(
                            role = Role.ISLAND,
                            step = BootStep.BROWSE,
                            message = t.message ?: "session falló",
                            retryable = true,
                        ),
                    )
                }
            } finally {
                active = false
                tearDownSession()
            }
        }
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    fun stop() {
        PctLog.event("session: stop")
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        active = false
    }

    fun detachFather() {
        sta.disconnect()
    }

    fun updateLoggingLevel(level: Int) {
        PctLog.minPriority = level
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    private suspend fun runSession() {
        topologyRole = Role.ISLAND
        parentSsid = null
        connectedMacsState.value = emptyList()
        lastChildCount = 0
        goCredentials = null
        goEndpointState.value = null
        browseWithProgress()

        val parent = StaAssociation.selectParent(
            observed = dnsSd.lastScan.value,
            excludeNodeId = nodeId.identifier,
            log = PctLog.logger("StaAssociation"),
        )

        val upstreamStaOk = if (parent != null) {
            val ssid = ControlAdvertisement.fromAndroidDnsSd(parent.service)?.groupSsid
                ?.takeIf { it.isNotBlank() }
                ?: parent.service.instanceName
            emitBoot(SessionStatus.Booting.JoiningParent(ssid))
            runCatching { sta.connect(parent.service, parent.deviceAddress) }
                .onSuccess {
                    parentSsid = ssid
                    PctLog.event("sta: conectado mac=${parent.deviceAddress} ssid=$ssid")
                }
                .onFailure { t ->
                    parentSsid = null
                    PctLog.event("sta: fallo mac=${parent.deviceAddress} ${t.message}")
                }
                .isSuccess
        } else {
            PctLog.event("sta: omitido (sin candidato)")
            false
        }

        emitBoot(SessionStatus.Booting.CreatingGo)
        goCredentials = try {
            groupOwner.start()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            emitBoot(
                SessionStatus.Failed(
                    role = Role.ISLAND,
                    step = BootStep.CREATE_GO,
                    message = t.message ?: "go start falló",
                    retryable = true,
                ),
            )
            return
        }
        topologyRole = if (upstreamStaOk) Role.BRIDGE else Role.ROOT
        emitBoot(SessionStatus.Booting.Publishing(topologyRole, parentSsid))
        publishAdvertisement(childCount = 0)
        PctLog.event("go: activo ssid=${goCredentials?.ssid.orEmpty()}")
        emitBoot(SessionStatus.Booting.WaitingGoAddress(topologyRole, parentSsid))
        if (goEndpointState.value != null) {
            emitBoot(SessionStatus.Booting.OpeningListeners(topologyRole, parentSsid))
        }

        monitorGoClients()
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    private suspend fun monitorGoClients() {
        while (currentCoroutineContext().isActive && active) {
            refreshGoClients()
            // LK-07: mientras la interfaz P2P no tenga IPv4 sondeamos rápido para abrir el listen cuanto antes.
            val interval = if (goEndpointState.value == null) GO_PENDING_INTERVAL_MS else GO_OBSERVE_INTERVAL_MS
            delay(interval.milliseconds)
        }
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    private suspend fun tearDownSession() {
        runCatching { dnsSd.unpublish() }
        runCatching { groupOwner.stop() }
        sta.disconnect()
        connectedMacsState.value = emptyList()
        goEndpointState.value = null
        goCredentials = null
        lastChildCount = 0
        topologyRole = Role.ISLAND
        parentSsid = null
        if (_bootStatus.value !is SessionStatus.Failed) {
            emitBoot(SessionStatus.Stopped)
        } else {
            roleState.value = Role.ISLAND
        }
        PctLog.event("session: fin")
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    private suspend fun publishAdvertisement(childCount: Int) {
        val creds = goCredentials ?: return
        dnsSd.publish(
            ControlAdvertisement(
                nodeId = nodeId,
                role = roleState.value,
                groupSsid = creds.ssid,
                groupPassphrase = creds.passphrase,
                childCount = childCount,
            ),
        )
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    private suspend fun refreshGoClients() {
        val group = p2pChannel.readGroupInfo()
        if (group == null || !group.isGroupOwner) {
            goEndpointState.value = null
            return
        }
        publishGoEndpoint(group)

        val macs = group.clientList.orEmpty()
            .mapNotNull { device -> device.deviceAddress.takeIf { it.isNotBlank() } }
        connectedMacsState.value = macs

        if (macs.size != lastChildCount) {
            lastChildCount = macs.size
            PctLog.event("go: hijos=$lastChildCount macs=$macs")
            publishAdvertisement(childCount = lastChildCount)
        }
    }

    /**
     * Misma lectura que hijos GO: [WifiP2pGroup.getInterface] → [Network] de ConnectivityManager.
     * LK-01: solo se publica un endpoint cuando la interfaz ya tiene IPv4; si no, `null`.
     */
    private fun publishGoEndpoint(group: WifiP2pGroup) {
        val iface = group.`interface`?.trim().orEmpty()
        val endpoint = if (iface.isEmpty()) null else resolveGoEndpoint(iface)
        if (goEndpointState.value != endpoint) {
            goEndpointState.value = endpoint
            if (endpoint != null) {
                PctLog.event(
                    "go: endpoint iface=$iface ipv4=${endpoint.hostIpv4} " +
                        "fe80=${endpoint.linkLocalIpv6?.hostAddress} network=${endpoint.network}",
                )
                val cur = _bootStatus.value
                if (cur is SessionStatus.Booting.WaitingGoAddress ||
                    cur is SessionStatus.Booting.Publishing
                ) {
                    emitBoot(SessionStatus.Booting.OpeningListeners(topologyRole, parentSsid))
                }
            }
        }
    }

    /**
     * Fuente principal: la interfaz del kernel ([NetworkInterface]). Hay OEM donde
     * ConnectivityManager no expone la interfaz P2P en `allNetworks` (LK-08), así que el
     * [Network] se adjunta solo si existe.
     */
    private fun resolveGoEndpoint(iface: String): GoControlEndpoint? {
        val nif = runCatching { NetworkInterface.getByName(iface) }.getOrNull()
        if (nif == null) {
            logEndpointPending("iface=$iface sin NetworkInterface")
            return null
        }
        var ipv4: String? = null
        var fe80: Inet6Address? = null
        var count = 0
        for (addr in nif.inetAddresses) {
            count++
            if (ipv4 == null && addr is Inet4Address && !addr.isLoopbackAddress) ipv4 = addr.hostAddress
            if (fe80 == null && addr is Inet6Address && addr.isLinkLocalAddress) fe80 = addr
        }
        if (ipv4 == null) {
            logEndpointPending("iface=$iface sin ipv4 addrs=$count")
            return null
        }
        val scoped = fe80?.let { addr ->
            runCatching { Inet6Address.getByAddress(null, addr.address, nif) }.getOrNull()
        }
        lastPendingReason = null
        return GoControlEndpoint(networkForInterface(iface), iface, ipv4, scoped)
    }

    private fun networkForInterface(iface: String): Network? {
        @Suppress("DEPRECATION")
        for (candidate in connectivity.allNetworks) {
            val lp: LinkProperties = connectivity.getLinkProperties(candidate) ?: continue
            if (lp.interfaceName == iface) return candidate
        }
        return null
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    private suspend fun browseWithProgress() {
        val windowSec = BROWSE_WINDOW.inWholeSeconds.toInt()
        coroutineScope {
            val browse = async { dnsSd.browse(BROWSE_WINDOW) }
            val ticker = launch {
                var elapsed = 0
                var lastLoggedCandidates = -1
                while (isActive && elapsed <= windowSec) {
                    val candidates = dnsSd.lastScan.value.size
                    emitBoot(
                        SessionStatus.Booting.Browsing(elapsed, windowSec, candidates),
                        log = elapsed == 0 || candidates != lastLoggedCandidates,
                    )
                    lastLoggedCandidates = candidates
                    delay(1.seconds)
                    elapsed++
                }
            }
            browse.await()
            ticker.cancel()
            emitBoot(
                SessionStatus.Booting.Browsing(
                    elapsedSec = windowSec,
                    windowSec = windowSec,
                    candidates = dnsSd.lastScan.value.size,
                ),
                log = false,
            )
        }
    }

    private fun emitBoot(status: SessionStatus, log: Boolean = true) {
        _bootStatus.value = status
        roleState.value = status.role
        if (log) {
            PctLog.event("status: ${status::class.simpleName} role=${status.role}")
        }
    }

    /** Una línea por motivo, no una por tick. */
    private fun logEndpointPending(reason: String) {
        if (lastPendingReason != reason) {
            lastPendingReason = reason
            PctLog.event("go: endpoint pendiente $reason")
        }
    }

    private companion object {
        private val BROWSE_WINDOW = 15.seconds
        private const val GO_OBSERVE_INTERVAL_MS = 3_000L
        private const val GO_PENDING_INTERVAL_MS = 1_000L
    }
}
