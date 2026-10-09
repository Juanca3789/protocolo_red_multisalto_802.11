package co.uan.pct.lib.core.radio

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.NEARBY_WIFI_DEVICES
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import androidx.annotation.RequiresPermission
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.radio.advertise.ControlAdvertisement
import co.uan.pct.lib.core.radio.advertise.ControlDnsSd
import co.uan.pct.lib.core.radio.android.P2pChannel
import co.uan.pct.lib.core.radio.group.GoCredentials
import co.uan.pct.lib.core.radio.group.GroupOwner
import co.uan.pct.lib.core.radio.sta.StaAssociation
import co.uan.pct.lib.core.types.NodeId
import co.uan.pct.lib.core.types.Role
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
) {
    private val appContext = context.applicationContext

    private val wifiP2pManager: WifiP2pManager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    private val p2pChannel = P2pChannel(appContext, wifiP2pManager, PctLog.logger("P2pChannel"))
    private val dnsSd = ControlDnsSd(p2pChannel)
    private val groupOwner = GroupOwner(p2pChannel, PctLog.logger("GroupOwner"))
    private val sta = StaAssociation(appContext, parentMacState, PctLog.logger("StaAssociation"))

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var active = false

    val role: StateFlow<Role> = roleState.asStateFlow()
    val connectedMacs: StateFlow<List<String>> = connectedMacsState.asStateFlow()

    private var goCredentials: GoCredentials? = null
    private var lastChildCount = 0

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    fun start() {
        if (active) return
        active = true
        PctLog.event("session: start")
        scope.launch {
            try {
                runSession()
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
        roleState.value = Role.ISLAND
        connectedMacsState.value = emptyList()
        lastChildCount = 0
        goCredentials = null

        dnsSd.browse(BROWSE_WINDOW)

        val parent = StaAssociation.selectParent(
            observed = dnsSd.lastScan.value,
            excludeNodeId = nodeId.identifier,
            log = PctLog.logger("StaAssociation"),
        )

        val upstreamStaOk = if (parent != null) {
            runCatching { sta.connect(parent.service, parent.deviceAddress) }
                .onSuccess {
                    PctLog.event("sta: conectado mac=${parent.deviceAddress} ssid=${parent.service.instanceName}")
                }
                .onFailure { t ->
                    PctLog.event("sta: fallo mac=${parent.deviceAddress} ${t.message}")
                }
                .isSuccess
        } else {
            PctLog.event("sta: omitido (sin candidato)")
            false
        }

        goCredentials = groupOwner.start()
        roleState.value = if (upstreamStaOk) Role.BRIDGE else Role.ROOT
        publishAdvertisement(childCount = 0)
        PctLog.event("go: activo ssid=${goCredentials?.ssid.orEmpty()}")

        monitorGoClients()
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    private suspend fun monitorGoClients() {
        while (currentCoroutineContext().isActive && active) {
            refreshGoClients()
            delay(GO_OBSERVE_INTERVAL_MS.milliseconds)
        }
    }

    @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES])
    private suspend fun tearDownSession() {
        runCatching { dnsSd.unpublish() }
        runCatching { groupOwner.stop() }
        sta.disconnect()
        roleState.value = Role.ISLAND
        connectedMacsState.value = emptyList()
        goCredentials = null
        lastChildCount = 0
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
        val group = p2pChannel.readGroupInfo() ?: return
        if (!group.isGroupOwner) return

        val macs = group.clientList.orEmpty()
            .mapNotNull { device -> device.deviceAddress.takeIf { it.isNotBlank() } }
        connectedMacsState.value = macs

        if (macs.size != lastChildCount) {
            lastChildCount = macs.size
            PctLog.event("go: hijos=$lastChildCount macs=$macs")
            publishAdvertisement(childCount = lastChildCount)
        }
    }

    private companion object {
        private val BROWSE_WINDOW = 15.seconds
        private const val GO_OBSERVE_INTERVAL_MS = 3_000L
    }
}
