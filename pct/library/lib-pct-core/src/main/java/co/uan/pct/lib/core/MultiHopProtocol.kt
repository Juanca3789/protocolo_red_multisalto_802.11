package co.uan.pct.lib.core

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import android.net.Network
import co.uan.pct.lib.core.data.Data
import co.uan.pct.lib.core.link.Link
import co.uan.pct.lib.core.link.table.NeighborEntry
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.radio.GoControlEndpoint
import co.uan.pct.lib.core.radio.Radio
import co.uan.pct.lib.core.types.NodeId
import co.uan.pct.lib.core.types.Role
import co.uan.pct.lib.core.types.SessionStatus
import co.uan.pct.lib.core.types.mergeListen
import co.uan.pct.lib.core.types.mergeUpstream
import co.uan.pct.lib.core.types.refine
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@OptIn(ExperimentalUuidApi::class)
class MultiHopProtocol(
    private val name: String,
) {
    private lateinit var multiHopContext: Context
    private var attachedId: NodeId? = null

    private val _state = MutableStateFlow(Role.ISLAND)
    val state: StateFlow<Role> = _state.asStateFlow()

    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Stopped)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private var mergeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mergeJob: Job? = null

    private val _connectedMacs = MutableStateFlow<List<String>>(emptyList())
    val connectedMacs: StateFlow<List<String>> = _connectedMacs.asStateFlow()

    private val _parentMac = MutableStateFlow<String?>(null)
    val parentMac: StateFlow<String?> = _parentMac.asStateFlow()

    private val _staNetwork = MutableStateFlow<Network?>(null)
    private val _parentBssid = MutableStateFlow<String?>(null)
    private val _goEndpoint = MutableStateFlow<GoControlEndpoint?>(null)

    private lateinit var link: Link
    private lateinit var data: Data
    val neighborTable: StateFlow<List<NeighborEntry>>
        get() = link.neighbors

    var loggingLevel: Int = Log.VERBOSE
        set(value) {
            field = value
            PctLog.minPriority = value
            if (::radio.isInitialized && running) {
                radio.updateLoggingLevel(value)
            }
        }

    private lateinit var radio: Radio
    private var running = false

    fun attach(context: Context): MultiHopProtocol {
        val displayLabel = "${name.trim()}_${Build.MODEL.trim()}".replace(' ', '_')
        multiHopContext = context.applicationContext
        attachedId = NodeId(Uuid.random(), displayLabel)
        link = Link(
            context = multiHopContext,
            localNodeId = attachedId!!,
            roleFlow = _state.asStateFlow(),
            goEndpointFlow = _goEndpoint.asStateFlow(),
            staNetworkFlow = _staNetwork.asStateFlow(),
            parentBssidFlow = _parentBssid.asStateFlow(),
        )
        data = Data(
            context = multiHopContext,
            localNodeId = attachedId!!,
            roleFlow = _state.asStateFlow(),
            goEndpointFlow = _goEndpoint.asStateFlow(),
            staNetworkFlow = _staNetwork.asStateFlow(),
            parentBssidFlow = _parentBssid.asStateFlow(),
            neighborsFlow = link.neighbors,
        )
        log(Log.DEBUG, "attach node=${attachedId!!.name}")
        return this
    }

    val nodeId: NodeId
        get() = attachedId ?: throw IllegalStateException("attach(context) first")

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun start() {
        if (running) {
            log(Log.DEBUG, "start ignored (already running)")
            return
        }
        if (attachedId == null) {
            throw IllegalStateException("attach(context) first")
        }
        PctLog.minPriority = loggingLevel
        try {
            log(Log.DEBUG, "start")
            radio = Radio(
                context = multiHopContext,
                nodeId = nodeId,
                roleState = _state,
                connectedMacsState = _connectedMacs,
                parentMacState = _parentMac,
                staNetworkState = _staNetwork,
                parentBssidState = _parentBssid,
                goEndpointState = _goEndpoint,
            )
            radio.start()
            link.start()
            data.start()
            watchStatus()
            running = true
            log(Log.DEBUG, "start ok")
        } catch (t: Throwable) {
            log(Log.ERROR, "start failed: ${t.message}")
            mergeJob?.cancel()
            mergeJob = null
            if (::data.isInitialized) {
                runCatching { data.stop() }
            }
            if (::link.isInitialized) {
                runCatching { link.stop() }
            }
            if (::radio.isInitialized) {
                runCatching { radio.stop() }
            }
            resetSessionState()
            throw t
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun stop() {
        if (!running) {
            log(Log.DEBUG, "stop ignored (not running)")
            return
        }
        try {
            log(Log.DEBUG, "stop")
            mergeJob?.cancel()
            mergeJob = null
            if (::data.isInitialized) {
                data.stop()
            }
            if (::link.isInitialized) {
                link.stop()
            }
            radio.stop()
        } catch (t: Throwable) {
            log(Log.WARN, "stop error: ${t.message}")
        } finally {
            running = false
            resetSessionState()
            log(Log.DEBUG, "stop finished")
        }
    }

    /** `(origen, payload)` de la cola FIFO de la capa data. */
    suspend fun pop(): Pair<NodeId, ByteArray> {
        check(::data.isInitialized) { "attach(context) first" }
        return data.pop()
    }

    /** Broadcast greedy (solo hop 1). */
    suspend fun broadcast(payload: ByteArray) {
        check(::data.isInitialized) { "attach(context) first" }
        data.broadcast(payload)
    }

    /** Unicast: next-jump según la tabla; hop a hop con ACK al ingreso. */
    suspend fun send(destination: NodeId, payload: ByteArray) {
        check(::data.isInitialized) { "attach(context) first" }
        data.send(destination, payload)
    }

    private fun watchStatus() {
        mergeJob?.cancel()
        mergeJob = mergeScope.launch {
            combine(
                radio.bootStatus,
                link.listenHealth,
                link.upstreamHealth,
                data.listenHealth,
                data.upstreamHealth,
            ) { boot, ll, lu, dl, du ->
                boot.refine(mergeListen(ll, dl), mergeUpstream(lu, du))
            }.collect { merged ->
                if (_status.value != merged) {
                    _status.value = merged
                    if (merged is SessionStatus.Online) {
                        PctLog.event(
                            "status: Online role=${merged.role} listen=${merged.listen} " +
                                "upstream=${merged.upstream}",
                        )
                    }
                }
            }
        }
    }

    private fun resetSessionState() {
        _state.value = Role.ISLAND
        _status.value = SessionStatus.Stopped
        _connectedMacs.value = emptyList()
        _parentMac.value = null
        _staNetwork.value = null
        _parentBssid.value = null
        _goEndpoint.value = null
    }

    private fun log(priority: Int, message: String) {
        PctLog.emit(LOG_TAG, priority, message)
    }

    companion object {
        private const val LOG_TAG = "MultiHopProtocol"

        val requiredPermissions: List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.INTERNET,
                )
            } else {
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.INTERNET,
                )
            }
    }
}
