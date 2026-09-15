package co.uan.pct.lib.core.internal

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import co.uan.pct.lib.core.api.PctNode
import co.uan.pct.lib.core.link.LinkLayer
import co.uan.pct.lib.core.link.LinkSnapshot
import co.uan.pct.lib.core.net.UserMessage
import co.uan.pct.lib.core.physical.NodeConfig
import co.uan.pct.lib.core.physical.PhysicalLayer
import co.uan.pct.lib.core.physical.PhysicalSnapshot
import co.uan.pct.lib.core.physical.parsePctUuid
import co.uan.pct.lib.core.physical.pctHex
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalUuidApi::class)
internal class PctNodeImpl : PctNode {
    private var nodeUuid: Uuid = Uuid.random()
    private var layer: PhysicalLayer? = null
    private var linkLayer: LinkLayer? = null
    private var initialized = false

    private val _snapshot = MutableStateFlow(PhysicalSnapshot())
    override val snapshot: StateFlow<PhysicalSnapshot> = _snapshot.asStateFlow()

    private val _link = MutableStateFlow(LinkSnapshot())
    override val link: StateFlow<LinkSnapshot> = _link.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _inbox = MutableSharedFlow<UserMessage>(extraBufferCapacity = 32)
    override val inbox: SharedFlow<UserMessage> = _inbox.asSharedFlow()

    override val nodeId: String
        get() = nodeUuid.pctHex()

    override fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        nodeUuid = loadOrCreateId(app)
        val physical = PhysicalLayer(
            nodeConfig = NodeConfig(nodeId = nodeUuid, nodeName = Build.MODEL),
            context = app,
        )
        val link = LinkLayer(nodeUuid.pctHex(), physical, app)
        layer = physical
        linkLayer = link
        _snapshot.value = physical.snapshot.value.copy(nodeId = nodeUuid.pctHex())
        physical.scope.launch {
            physical.snapshot.collect { _snapshot.value = it }
        }
        physical.scope.launch {
            physical.logs.collect { _logs.tryEmit(it) }
        }
        physical.scope.launch {
            link.snapshot.collect { _link.value = it }
        }
        physical.scope.launch {
            link.logs.collect { _logs.tryEmit(it) }
        }
        physical.scope.launch {
            link.mesh.inbox.collect { _inbox.tryEmit(it) }
        }
        initialized = true
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    override fun start() {
        val physical = layer ?: error("PctNode.init(context) antes de start()")
        val link = linkLayer ?: error("L2 no inicializado")
        _logs.tryEmit("arranco radio, enlace y mensajes")
        link.start()
        physical.start()
    }

    override fun close() {
        val physical = layer ?: return
        val link = linkLayer
        initialized = false
        layer = null
        linkLayer = null
        link?.close()
        runBlocking {
            withTimeoutOrNull(8_000) {
                runCatching { physical.close() }
            }
        }
    }

    override fun sendUser(destinationNid: String, payload: ByteArray) {
        val link = linkLayer ?: return
        _logs.tryEmit("envio a ${destinationNid.take(8)} ${payload.size} B")
        link.sendUser(destinationNid, payload)
    }

    private fun loadOrCreateId(context: Context): Uuid {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_NID, null)
        parsePctUuid(stored.orEmpty())?.let { return it }
        val created = Uuid.random()
        prefs.edit().putString(KEY_NID, created.pctHex()).apply()
        return created
    }

    private companion object {
        const val PREFS = "pct_node"
        const val KEY_NID = "node_id"
    }
}
