package co.uan.pct.lib.core

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import android.net.Network
import co.uan.pct.lib.core.link.ControlLink
import co.uan.pct.lib.core.link.NeighborTable
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.radio.Radio
import co.uan.pct.lib.core.types.NodeId
import co.uan.pct.lib.core.types.Role
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(ExperimentalUuidApi::class)
class MultiHopProtocol(
    private val name: String,
) {
    private lateinit var multiHopContext: Context
    private var attachedId: NodeId? = null
    private val incoming = Channel<Pair<NodeId, ByteArray>>(capacity = Channel.UNLIMITED)

    private val _state = MutableStateFlow(Role.ISLAND)
    val state: StateFlow<Role> = _state.asStateFlow()

    private val _connectedMacs = MutableStateFlow<List<String>>(emptyList())
    val connectedMacs: StateFlow<List<String>> = _connectedMacs.asStateFlow()

    private val _parentMac = MutableStateFlow<String?>(null)
    val parentMac: StateFlow<String?> = _parentMac.asStateFlow()

    private val _staNetwork = MutableStateFlow<Network?>(null)

    private val neighborTableModel = NeighborTable()
    val neighborTable: StateFlow<List<Pair<NodeId, Int>>> = neighborTableModel.entries

    var loggingLevel: Int = Log.VERBOSE
        set(value) {
            field = value
            PctLog.minPriority = value
            if (::radio.isInitialized && running) {
                radio.updateLoggingLevel(value)
            }
        }

    private lateinit var radio: Radio
    private lateinit var controlLink: ControlLink
    private var running = false

    fun attach(context: Context): MultiHopProtocol {
        val displayLabel = "${name.trim()}_${Build.MODEL.trim()}".replace(' ', '_')
        multiHopContext = context.applicationContext
        attachedId = NodeId(Uuid.random(), displayLabel)
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
            )
            neighborTableModel.addEntry(nodeId, hops = 0)
            controlLink = ControlLink(
                context = multiHopContext,
                localNodeId = nodeId,
                roleFlow = _state,
                staNetworkFlow = _staNetwork.asStateFlow(),
                neighborTable = neighborTableModel,
            )
            controlLink.start()
            radio.start()
            running = true
            log(Log.DEBUG, "start ok")
        } catch (t: Throwable) {
            log(Log.ERROR, "start failed: ${t.message}")
            if (::controlLink.isInitialized) {
                runCatching { controlLink.stop() }
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
            if (::controlLink.isInitialized) {
                controlLink.stop()
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

    suspend fun pop(): Pair<NodeId, ByteArray> = incoming.receive()

    suspend fun send(destination: NodeId, payload: ByteArray) {
    }

    private fun resetSessionState() {
        _state.value = Role.ISLAND
        _connectedMacs.value = emptyList()
        _parentMac.value = null
        _staNetwork.value = null
        neighborTableModel.clear()
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
