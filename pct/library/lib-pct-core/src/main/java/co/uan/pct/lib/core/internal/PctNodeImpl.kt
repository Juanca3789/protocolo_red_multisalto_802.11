package co.uan.pct.lib.core.internal

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import co.uan.pct.lib.core.api.NodePhase
import co.uan.pct.lib.core.api.NeighborSnapshot
import co.uan.pct.lib.core.api.PctConfig
import co.uan.pct.lib.core.api.PctDebugCandidate
import co.uan.pct.lib.core.api.PctDebugSnapshot
import co.uan.pct.lib.core.api.PctEvent
import co.uan.pct.lib.core.api.PctNode
import co.uan.pct.lib.core.api.RouteSnapshot
import co.uan.pct.lib.core.api.TopologyNode
import co.uan.pct.lib.core.api.TopologySnapshot
import co.uan.pct.lib.core.internal.link.LinkOrchestrator
import co.uan.pct.lib.core.internal.link.NeighborRegistry
import co.uan.pct.lib.core.internal.p2p.DnsSdRepository
import co.uan.pct.lib.core.internal.p2p.GoRepository
import co.uan.pct.lib.core.internal.p2p.P2pChannelHolder
import co.uan.pct.lib.core.internal.p2p.model.GoState
import co.uan.pct.lib.core.internal.p2p.model.PctCtrlRecord
import co.uan.pct.lib.core.internal.route.RouteOrchestrator
import co.uan.pct.lib.core.internal.sta.LegacyStaRepository
import co.uan.pct.lib.core.internal.sta.StaState
import co.uan.pct.lib.core.internal.util.ParentSelector
import co.uan.pct.lib.core.internal.util.PctNetworkHelper
import co.uan.pct.lib.core.internal.util.PctNid
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class PctNodeImpl : PctNode {

    companion object {
        private const val LOG_TAG = "PctMesh"
    }

    private var scope: CoroutineScope? = null
    private var config: PctConfig = PctConfig()

    private var p2p: P2pChannelHolder? = null
    private var go: GoRepository? = null
    private var dns: DnsSdRepository? = null
    private var sta: LegacyStaRepository? = null
    private var connectivityManager: ConnectivityManager? = null
    private var registry: NeighborRegistry? = null
    private var routeOrch: RouteOrchestrator? = null
    private var link: LinkOrchestrator? = null

    private var started = AtomicBoolean(false)
    private var closed = AtomicBoolean(false)
    private var initialized = AtomicBoolean(false)

    private var role: String = "ISLAND"
    private var parentNode: TopologyNode? = null
    private var parentStaRecord: PctCtrlRecord? = null
    private var bootstrapJob: Job? = null
    private var currentAction: String = "Sin iniciar"

    private val _nodeId = MutableStateFlow("")
    override val nodeId: String
        get() = _nodeId.value

    private val _phase = MutableStateFlow(NodePhase.ISLAND)
    override val phase: StateFlow<NodePhase> = _phase.asStateFlow()

    private val _topology = MutableStateFlow(
        TopologySnapshot(
            self = TopologyNode(nodeId = "", role = "ISLAND", hop = 0),
        ),
    )
    override val topology: StateFlow<TopologySnapshot> = _topology.asStateFlow()

    private val _debug = MutableStateFlow(PctDebugSnapshot())
    override val debug: StateFlow<PctDebugSnapshot> = _debug.asStateFlow()

    private val _events = MutableSharedFlow<PctEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<PctEvent> = _events.asSharedFlow()

    private val _neighbors = MutableStateFlow(NeighborSnapshot())
    override val neighbors: StateFlow<NeighborSnapshot> = _neighbors.asStateFlow()

    private val _routes = MutableStateFlow(RouteSnapshot())
    override val routes: StateFlow<RouteSnapshot> = _routes.asStateFlow()

    override fun init(context: Context, config: PctConfig) {
        if (initialized.getAndSet(true)) {
            emitLog("init() ignorado: ya inicializado")
            return
        }
        closed.set(false)
        this.config = config
        val app = context.applicationContext as Application
        val id = UUID.randomUUID().toString().replace("-", "")
        _nodeId.value = id

        val holder = P2pChannelHolder(app)
        holder.register()
        holder.forceTeardownP2p()
        p2p = holder
        go = GoRepository(holder)
        dns = DnsSdRepository(holder).also { it.setLocalNodeId(id) }
        sta = LegacyStaRepository(app)
        connectivityManager = app.getSystemService(ConnectivityManager::class.java)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val reg = NeighborRegistry()
        registry = reg
        val routeOrchInstance = RouteOrchestrator(
            selfNid = { nodeId },
            selfRole = { role },
            selfHop = { currentHop() },
            registry = reg,
            log = { emitLog(it) },
            emitUserMessage = { from, text ->
                _events.tryEmit(PctEvent.UserMessage(from, text))
            },
        )
        routeOrch = routeOrchInstance
        link = LinkOrchestrator(
            parentScope = scope!!,
            config = config,
            registry = reg,
            routes = routeOrchInstance,
            connectivityManager = connectivityManager!!,
            selfNid = { nodeId },
            selfRole = { role },
            selfHop = { currentHop() },
            parentNid = { parentNode?.nodeId },
            log = { emitLog(it) },
            onTopologyChanged = { scope?.launch { publishTopology() } },
        )

        scope?.launch {
            dns?.events?.collect { msg -> emitLog("DNS-SD: $msg") }
        }
        scope?.launch { collectDebug() }
        scope?.launch { collectTopologyReactively(routeOrchInstance) }

        setAction("Inicializado; esperando start() (permisos)")
        publishTopology()
        emitLog("init OK node_id=$id (P2P limpio)")
        setPhase(NodePhase.ISLAND)
    }

    override fun start() {
        if (!initialized.get()) {
            emitError("start() sin init()")
            return
        }
        if (closed.get()) {
            emitError("start() tras close()")
            return
        }
        if (!started.compareAndSet(false, true)) {
            emitLog("start() ignorado: bootstrap ya lanzado")
            return
        }
        val s = scope ?: return
        setAction("Lanzando bootstrap…")
        emitLog("start(): bootstrap scan→join|root")
        bootstrapJob = s.launch {
            runCatching { bootstrap() }
                .onFailure { e ->
                    emitError("Bootstrap falló: ${e.message ?: e.javaClass.simpleName}")
                    setAction("Bootstrap falló: ${e.message}")
                    setPhase(NodePhase.ISLAND)
                }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        bootstrapJob?.cancel()
        bootstrapJob = null
        started.set(false)

        emitLog("close(): deteniendo L2/L3/DNS/STA/GO…")
        setAction("Cerrando P2P…")
        runCatching { link?.close() }
        link = null
        routeOrch = null
        registry = null
        runCatching { dns?.stopDiscovery() }
        runCatching { dns?.stopAdvertising() }
        runCatching { dns?.close() }
        runCatching { sta?.disconnect() }
        runCatching { go?.forceRemoveGroup() }
        runCatching { go?.close() }
        runCatching { p2p?.forceTeardownP2p() }
        runCatching { p2p?.unregister() }
        p2p = null
        go = null
        dns = null
        sta = null
        connectivityManager = null

        scope?.cancel()
        scope = null
        initialized.set(false)
        role = "ISLAND"
        parentNode = null
        parentStaRecord = null
        _neighbors.value = NeighborSnapshot()
        _routes.value = RouteSnapshot()
        _debug.value = PctDebugSnapshot(action = "Cerrado")
        setPhase(NodePhase.ISLAND)
    }

    override fun sendUser(destinationNid: String, payload: ByteArray) {
        val r = routeOrch ?: return
        scope?.launch {
            r.sendUser(destinationNid, payload)
        }
    }

    private fun currentHop(): Int = when {
        role == "ROOT" -> 0
        parentNode != null -> parentNode!!.hop + 1
        else -> 0
    }

    private suspend fun collectTopologyReactively(routeOrchInstance: RouteOrchestrator) {
        combine(
            routeOrchInstance.neighbors,
            routeOrchInstance.routes,
        ) { neighbors, routes ->
            neighbors to routes
        }.collect { (neighbors, routes) ->
            _neighbors.value = neighbors
            _routes.value = routes
            publishTopology()
        }
    }

    private suspend fun collectDebug() {
        val goRepo = go ?: return
        val dnsRepo = dns ?: return
        val staRepo = sta ?: return
        combine(
            goRepo.goState,
            staRepo.staState,
            dnsRepo.isDiscovering,
            dnsRepo.isAdvertising,
            dnsRepo.diagnostics,
        ) { goState, staState, discovering, advertising, diag ->
            DebugPartial(goState, staState, discovering, advertising, diag)
        }.combine(dnsRepo.parentCandidates) { partial, candidates ->
            partial to candidates
        }.combine(goRepo.clients) { pair, clients ->
            buildDebug(
                goState = pair.first.go,
                staState = pair.first.sta,
                discovering = pair.first.discovering,
                advertising = pair.first.advertising,
                diag = pair.first.diag,
                candidates = pair.second,
                goClientCount = clients.size,
            )
        }.collect { snap ->
            val stats = link?.linkStats()
            _debug.value = if (stats != null) {
                snap.copy(
                    ctrlLinksOpen = stats.first,
                    dataLinksOpen = stats.second,
                    dataLinksReconnecting = stats.third,
                )
            } else {
                snap
            }
        }
    }

    private data class DebugPartial(
        val go: GoState,
        val sta: StaState,
        val discovering: Boolean,
        val advertising: Boolean,
        val diag: co.uan.pct.lib.core.internal.p2p.model.DnsSdDiagnostics,
    )

    private fun buildDebug(
        goState: GoState,
        staState: StaState,
        discovering: Boolean,
        advertising: Boolean,
        diag: co.uan.pct.lib.core.internal.p2p.model.DnsSdDiagnostics,
        candidates: List<co.uan.pct.lib.core.internal.p2p.model.PctCtrlCandidate>,
        goClientCount: Int,
    ): PctDebugSnapshot {
        val (goStatus, goSsid, goOwner) = when (goState) {
            GoState.Idle -> Triple("idle", null, null)
            GoState.Creating -> Triple("creando…", null, null)
            is GoState.Ready -> Triple(
                "ready owner=${goState.isGroupOwner}",
                goState.ssid,
                goState.isGroupOwner,
            )
            is GoState.Error -> Triple("ERROR: ${goState.message}", null, null)
        }
        val (staStatus, staSsid) = when (staState) {
            StaState.Idle -> "idle" to null
            StaState.Connecting -> "conectando…" to null
            is StaState.Connected -> "conectado" to staState.ssid
            is StaState.Error -> "ERROR: ${staState.message}" to null
        }
        return PctDebugSnapshot(
            action = currentAction,
            goStatus = goStatus,
            goSsid = goSsid,
            goIsOwner = goOwner,
            goClientCount = goClientCount,
            staStatus = staStatus,
            staSsid = staSsid,
            dnsDiscovering = discovering,
            dnsAdvertising = advertising,
            dnsPhase = diag.phase.name,
            peerCount = diag.peerCount,
            servicesSeen = diag.servicesSeen,
            pctCtrlSeen = diag.pctCtrlSeen,
            txtCallbacks = diag.txtCallbacks,
            discoveryTicks = diag.discoveryTicks,
            hint = diag.hint,
            candidates = candidates.map { c ->
                PctDebugCandidate(
                    deviceName = c.deviceName,
                    nodeId = c.record.nid,
                    role = c.record.role,
                    hop = c.record.hop,
                    goSsid = c.record.goSsid,
                    deviceAddress = c.deviceAddress,
                    instanceName = c.instanceName,
                )
            },
        )
    }

    private fun setAction(action: String) {
        currentAction = action
        _debug.value = _debug.value.copy(action = action)
        emitLog("→ $action")
    }

    private suspend fun bootstrap() {
        val goRepo = go ?: return
        val dnsRepo = dns ?: return
        val staRepo = sta ?: return

        setAction("Apagando GO residual (necesario para scan DNS-SD)")
        if (goRepo.goState.value is GoState.Ready) {
            emitLog("GO activo detectado; removeGroup()")
            goRepo.removeGroup()
            withTimeoutOrNull(8_000L) {
                goRepo.goState.first { it is GoState.Idle || it is GoState.Error }
            } ?: emitLog("Timeout removeGroup; sigo con scan")
        } else {
            emitLog("GO ya idle")
        }

        setPhase(NodePhase.SCANNING)
        setAction(
            "SCANNING: discovery DNS-SD _pct-ctrl (${config.scanSettleMs}ms settle). " +
                "GO debe estar apagado.",
        )
        dnsRepo.startDiscovery(candidateSettleMs = config.scanSettleMs)

        withTimeoutOrNull(config.scanSettleMs + 8_000L) {
            dnsRepo.discoveryFinished.first()
        } ?: emitLog("Timeout fin escaneo; uso candidatos actuales")

        var candidates = dnsRepo.parentCandidates.value
        if (candidates.isEmpty() && dnsRepo.diagnostics.value.peerCount > 0) {
            emitLog("Peers sin _pct-ctrl; esperando anuncio del padre (10s)…")
            withTimeoutOrNull(10_000L) {
                dnsRepo.parentCandidates.first { it.isNotEmpty() }
            }
            candidates = dnsRepo.parentCandidates.value
        }
        emitLog(
            "Scan fin: peers=${dnsRepo.diagnostics.value.peerCount} " +
                "pct=${dnsRepo.diagnostics.value.pctCtrlSeen} " +
                "candidatos=${candidates.size} " +
                "hint=${dnsRepo.diagnostics.value.hint.ifBlank { "—" }}",
        )

        val best = ParentSelector.best(candidates, nodeId)
        publishTopology()

        if (best != null) {
            var chosen = best
            if (!PctNid.isFull(chosen.record.nid)) {
                val parentAddr = chosen.deviceAddress
                emitLog(
                    "Padre ${chosen.deviceName}: nid prefijo DNS=${chosen.record.nid}; " +
                        "esperando TXT (4s)…",
                )
                withTimeoutOrNull(4_000L) {
                    dnsRepo.parentCandidates.first { list ->
                        list.any {
                            it.deviceAddress == parentAddr && PctNid.isFull(it.record.nid)
                        }
                    }
                }
                chosen = ParentSelector.best(dnsRepo.parentCandidates.value, nodeId) ?: chosen
                if (PctNid.isFull(chosen.record.nid)) {
                    emitLog("TXT OK: nid completo ${chosen.record.nid.take(8)}…")
                } else {
                    emitLog(
                        "Sin TXT completo; nid se resolverá en HELLO L2 " +
                            "(prefijo ${chosen.record.nid})",
                    )
                }
            }
            setPhase(NodePhase.JOINING)
            dnsRepo.selectParent(chosen.deviceAddress)
            dnsRepo.stopDiscovery()
            parentStaRecord = chosen.record
            parentNode = TopologyNode(
                nodeId = chosen.record.nid,
                role = chosen.record.role,
                hop = chosen.record.hop,
                goSsid = chosen.record.goSsid,
            )
            publishTopology()

            if (config.autoActivateGoAfterSta) {
                setAction(
                    "JOINING: STA → GO BRIDGE (padre ${chosen.record.nid.take(8)}…)",
                )
                staRepo.connectIfSupported(chosen.record)
                val connected = withTimeoutOrNull(config.bootstrapTimeoutMs) {
                    staRepo.staState.first { it is StaState.Connected || it is StaState.Error }
                }
                when (connected) {
                    is StaState.Connected -> {
                        emitLog("STA OK ssid=${connected.ssid}")
                        activateAsMember()
                    }
                    is StaState.Error -> {
                        emitError("STA falló: ${connected.message}; fallback ROOT")
                        startAsRoot()
                    }
                    else -> {
                        emitError("STA timeout ${config.bootstrapTimeoutMs}ms; fallback ROOT")
                        startAsRoot()
                    }
                }
            } else {
                setAction(
                    "JOINING: STA legacy → ${chosen.record.goSsid} " +
                        "(padre ${chosen.record.nid.take(8)}… role=${chosen.record.role})",
                )
                staRepo.connectIfSupported(chosen.record)
                val connected = withTimeoutOrNull(config.bootstrapTimeoutMs) {
                    staRepo.staState.first { it is StaState.Connected || it is StaState.Error }
                }
                when (connected) {
                    is StaState.Connected -> {
                        emitLog("STA OK ssid=${connected.ssid}")
                        link?.onStaConnected(
                            parentNidValue = chosen.record.nid,
                            parentRole = chosen.record.role,
                            parentHop = chosen.record.hop,
                            network = staRepo.activeNetwork,
                        )
                        setAction("STA OK; auto GO desactivado — queda en JOINING")
                        setPhase(NodePhase.JOINING)
                    }
                    is StaState.Error -> {
                        emitError("STA falló: ${connected.message}; fallback ROOT")
                        startAsRoot()
                    }
                    else -> {
                        emitError("STA timeout ${config.bootstrapTimeoutMs}ms; fallback ROOT")
                        startAsRoot()
                    }
                }
            }
        } else {
            emitLog("Sin candidatos PCT; este nodo será ROOT")
            startAsRoot()
        }
    }

    private suspend fun activateAsMember() {
        val goRepo = go ?: return
        role = "BRIDGE"
        setPhase(NodePhase.MEMBER)
        publishTopology()
        setAction("MEMBER: createGroup() BRIDGE → L2 TCP al padre")

        when (val current = goRepo.goState.value) {
            is GoState.Ready -> {
                emitLog("GO ya Ready; anuncio BRIDGE sin recreate")
                advertise(role = "BRIDGE", current)
                return
            }
            else -> Unit
        }

        goRepo.createGroup()
        val ready = awaitGoReady(goRepo, label = "BRIDGE")
        when (ready) {
            is GoState.Ready -> {
                emitLog("GO BRIDGE listo ssid=${ready.ssid} owner=${ready.isGroupOwner}")
                advertise(role = "BRIDGE", ready)
            }
            is GoState.Error -> {
                setAction("ERROR createGroup BRIDGE: ${ready.message}")
                emitError("createGroup BRIDGE: ${ready.message}")
                publishTopology()
            }
            else -> {
                goRepo.requestGroupInfo()
                val late = withTimeoutOrNull(3_000L) {
                    goRepo.goState.first { it is GoState.Ready || it is GoState.Error }
                }
                if (late is GoState.Ready) {
                    emitLog("GO BRIDGE listo (late) ssid=${late.ssid}")
                    advertise(role = "BRIDGE", late)
                } else {
                    setAction("ERROR timeout createGroup BRIDGE")
                    emitError("Timeout createGroup BRIDGE")
                    publishTopology()
                }
            }
        }
    }

    private suspend fun startAsRoot() {
        val goRepo = go ?: return
        val dnsRepo = dns ?: return
        dnsRepo.stopDiscovery()
        role = "ROOT"
        parentNode = null
        parentStaRecord = null
        setPhase(NodePhase.ROOT)
        publishTopology()
        setAction("ROOT: createGroup() → anunciar _pct-ctrl")
        goRepo.createGroup()
        val ready = awaitGoReady(goRepo, label = "ROOT")
        when (ready) {
            is GoState.Ready -> {
                emitLog("GO ROOT listo ssid=${ready.ssid} owner=${ready.isGroupOwner}")
                advertise(role = "ROOT", ready)
            }
            is GoState.Error -> {
                setAction("ERROR createGroup ROOT: ${ready.message}")
                emitError("createGroup ROOT: ${ready.message}")
                publishTopology()
            }
            else -> {
                setAction("ERROR timeout createGroup ROOT")
                emitError("Timeout createGroup ROOT")
                publishTopology()
            }
        }
    }

    private suspend fun awaitGoReady(goRepo: GoRepository, label: String): GoState? {
        return withTimeoutOrNull(18_000L) {
            goRepo.goState.first { state ->
                when (state) {
                    is GoState.Ready -> true
                    is GoState.Error -> {
                        emitLog("GO $label Error: ${state.message}")
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun advertise(role: String, ready: GoState.Ready) {
        val dnsRepo = dns ?: return
        if (!ready.isGroupOwner) {
            setAction("ERROR: no somos GO; no se anuncia")
            emitError("No es GO; no se anuncia")
            return
        }
        if (ready.ssid.isBlank() || ready.psk.isBlank()) {
            setAction("ERROR: SSID/PSK vacío tras createGroup")
            emitError("SSID/PSK vacío; recreate pendiente")
            return
        }
        this.role = role
        setAction("Anunciando DNS-SD _pct-ctrl como $role ssid=${ready.ssid}")
        dnsRepo.advertiseCtrl(
            nid = nodeId,
            goSsid = ready.ssid,
            goPsk = ready.psk,
            role = role,
        )
        emitLog("Anuncio registrado role=$role ssid=${ready.ssid}")
        link?.onGoReady(listenNetwork = null)
        if (role != "ROOT") {
            connectUpstreamAfterGoReady()
        }
        publishTopology()
        setPhase(if (role == "ROOT") NodePhase.ROOT else NodePhase.MEMBER)
        setAction(
            if (role == "ROOT") {
                "ROOT operativo: GO + L2 accept. Esperando hijos."
            } else {
                "MEMBER operativo: STA + GO + L2 accept."
            },
        )
    }

    /** Tras createGroup, la STA al padre suele caer: re-asociar una vez y conectar L2 por TCP. */
    private suspend fun staForUpstream(): Network? {
        val staRepo = sta ?: return null
        val record = parentStaRecord
        if (record != null &&
            (staRepo.activeNetwork == null || staRepo.staState.value !is StaState.Connected)
        ) {
            emitLog("Re-asociando STA a ${record.goSsid}…")
            staRepo.connectIfSupported(record)
            withTimeoutOrNull(20_000L) {
                staRepo.staState.first { it is StaState.Connected || it is StaState.Error }
            }
        }
        return staRepo.activeNetwork?.also {
            emitLog("Red STA net=$it para L2 upstream")
        } ?: run {
            emitError("Sin red STA al padre")
            null
        }
    }

    private fun connectUpstreamAfterGoReady() {
        val parent = parentNode ?: return
        val l = link ?: return
        scope?.launch {
            val network = staForUpstream() ?: return@launch
            emitLog("L2 TCP → ${PctNetworkHelper.DEFAULT_P2P_GW}:${config.ctrlPort} padre ${parent.nodeId.take(8)}…")
            l.onStaConnected(
                parentNidValue = parent.nodeId,
                parentRole = parent.role,
                parentHop = parent.hop,
                network = network,
            )
        }
    }

    private fun publishTopology() {
        val neighborSnap = routeOrch?.neighbors?.value ?: _neighbors.value
        val routeSnap = routeOrch?.routes?.value ?: _routes.value
        _neighbors.value = neighborSnap
        _routes.value = routeSnap

        val peers = dns?.parentCandidates?.value.orEmpty().map { c ->
            TopologyNode(
                nodeId = c.record.nid,
                role = c.record.role,
                hop = c.record.hop,
                goSsid = c.record.goSsid,
            )
        }
        val hop = currentHop()
        val goSsid = (go?.goState?.value as? GoState.Ready)?.ssid

        val children = neighborSnap.neighbors
            .filter { it.iface == co.uan.pct.lib.core.api.NeighborIface.DOWNSTREAM }
            .map { n ->
                TopologyNode(
                    nodeId = n.neighborNid,
                    role = n.role,
                    hop = n.hop,
                    goSsid = null,
                )
            }

        val upstream = neighborSnap.neighbors
            .firstOrNull { it.iface == co.uan.pct.lib.core.api.NeighborIface.UPSTREAM }
        if (upstream != null && PctNid.isFull(upstream.neighborNid)) {
            parentNode = TopologyNode(
                nodeId = upstream.neighborNid,
                role = upstream.role.ifBlank { parentNode?.role ?: "ROOT" },
                hop = upstream.hop,
                goSsid = parentNode?.goSsid,
            )
        }
        val parentFromL2 = upstream?.let {
            TopologyNode(
                nodeId = it.neighborNid,
                role = it.role,
                hop = it.hop,
                goSsid = parentNode?.goSsid,
            )
        }
        val effectiveParent = parentFromL2 ?: parentNode

        val snapshot = TopologySnapshot(
            self = TopologyNode(
                nodeId = nodeId.ifBlank { "pending" },
                role = role,
                hop = hop,
                goSsid = goSsid,
            ),
            parent = effectiveParent,
            children = children,
            knownPeers = peers,
        )
        _topology.value = snapshot
        _events.tryEmit(PctEvent.TopologyChanged(snapshot))
    }

    private fun setPhase(phase: NodePhase) {
        _phase.value = phase
        _events.tryEmit(PctEvent.PhaseChanged(phase))
        emitLog("FASE → ${phase.name}")
    }

    private fun emitLog(message: String) {
        Log.i(LOG_TAG, message)
        _events.tryEmit(PctEvent.Log(message))
    }

    private fun emitError(message: String) {
        Log.e(LOG_TAG, message)
        _events.tryEmit(PctEvent.Error(message))
    }
}
