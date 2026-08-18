package co.uan.pct.lib.core.internal

import android.app.Application
import android.content.Context
import co.uan.pct.lib.core.api.NodePhase
import co.uan.pct.lib.core.api.PctConfig
import co.uan.pct.lib.core.api.PctDebugCandidate
import co.uan.pct.lib.core.api.PctDebugSnapshot
import co.uan.pct.lib.core.api.PctEvent
import co.uan.pct.lib.core.api.PctNode
import co.uan.pct.lib.core.api.TopologyNode
import co.uan.pct.lib.core.api.TopologySnapshot
import co.uan.pct.lib.core.internal.ctrl.HelloHub
import co.uan.pct.lib.core.internal.ctrl.HelloPayload
import co.uan.pct.lib.core.internal.p2p.DnsSdRepository
import co.uan.pct.lib.core.internal.p2p.GoRepository
import co.uan.pct.lib.core.internal.p2p.P2pChannelHolder
import co.uan.pct.lib.core.internal.p2p.model.GoState
import co.uan.pct.lib.core.internal.sta.LegacyStaRepository
import co.uan.pct.lib.core.internal.sta.StaState
import co.uan.pct.lib.core.internal.util.ParentSelector
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class PctNodeImpl : PctNode {

    private var scope: CoroutineScope? = null
    private var config: PctConfig = PctConfig()

    private var p2p: P2pChannelHolder? = null
    private var go: GoRepository? = null
    private var dns: DnsSdRepository? = null
    private var sta: LegacyStaRepository? = null
    private var helloHub: HelloHub? = null

    private var started = AtomicBoolean(false)
    private var closed = AtomicBoolean(false)
    private var initialized = AtomicBoolean(false)

    private var role: String = "ISLAND"
    private var parentNode: TopologyNode? = null
    /** Clientes SoftAP (MAC) — provisional hasta HELLO. */
    private var macChildNodes: List<TopologyNode> = emptyList()
    /** Hijos con UUID PCT real vía HELLO UDP. */
    private val helloChildNodes = LinkedHashMap<String, TopologyNode>()
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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        helloHub = HelloHub(scope!!, config.ctrlPort)

        scope?.launch {
            dns?.events?.collect { msg -> emitLog("DNS-SD: $msg") }
        }
        scope?.launch { collectDebug() }
        scope?.launch { collectGoClients() }
        scope?.launch { collectHellos() }

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

        emitLog("close(): deteniendo DNS/STA/GO/HELLO…")
        setAction("Cerrando P2P…")
        runCatching { helloHub?.close() }
        helloHub = null
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

        scope?.cancel()
        scope = null
        initialized.set(false)
        role = "ISLAND"
        parentNode = null
        macChildNodes = emptyList()
        helloChildNodes.clear()
        _debug.value = PctDebugSnapshot(action = "Cerrado")
        setPhase(NodePhase.ISLAND)
    }

    private suspend fun collectHellos() {
        val hub = helloHub ?: return
        hub.hellos.collect { hello ->
            if (hello.nid == nodeId) return@collect
            val node = TopologyNode(
                nodeId = hello.nid,
                role = hello.role.ifBlank { "CHILD" },
                hop = hello.hop,
                goSsid = null,
            )
            val prev = helloChildNodes[hello.nid]
            helloChildNodes[hello.nid] = node
            if (prev?.nodeId != node.nodeId || prev.role != node.role || prev.hop != node.hop) {
                emitLog("HELLO hijo nid=${hello.nid.take(8)}… role=${hello.role} hop=${hello.hop}")
                publishTopology()
            }
        }
    }

    private suspend fun collectGoClients() {
        val goRepo = go ?: return
        var lastCount = -1
        goRepo.clients.collect { clients ->
            macChildNodes = clients.map { c ->
                TopologyNode(
                    nodeId = c.deviceAddress.replace(":", "").ifBlank { c.deviceName },
                    role = "CHILD",
                    hop = 0,
                    goSsid = null,
                )
            }
            if (clients.isEmpty() && helloChildNodes.isNotEmpty()) {
                helloChildNodes.clear()
                emitLog("GO sin clientes SoftAP; limpio hijos HELLO")
            }
            _debug.value = _debug.value.copy(goClientCount = clients.size)
            if (clients.size != lastCount) {
                lastCount = clients.size
                emitLog(
                    "GO SoftAP clients=${clients.size} HELLO hijos=${helloChildNodes.size}",
                )
            }
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
            _debug.value = snap
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

        val candidates = dnsRepo.parentCandidates.value
        emitLog(
            "Scan fin: peers=${dnsRepo.diagnostics.value.peerCount} " +
                "pct=${dnsRepo.diagnostics.value.pctCtrlSeen} " +
                "candidatos=${candidates.size} " +
                "hint=${dnsRepo.diagnostics.value.hint.ifBlank { "—" }}",
        )
        candidates.forEachIndexed { i, c ->
            emitLog(
                "  cand[$i] ${c.deviceName} nid=${c.record.nid.take(8)}… " +
                    "role=${c.record.role} hop=${c.record.hop} ssid=${c.record.goSsid}",
            )
        }

        val best = ParentSelector.best(candidates, nodeId)
        publishTopology()

        if (best != null) {
            setPhase(NodePhase.JOINING)
            setAction(
                "JOINING: STA legacy → ${best.record.goSsid} " +
                    "(padre ${best.record.nid.take(8)}… role=${best.record.role})",
            )
            dnsRepo.selectParent(best.deviceAddress)
            dnsRepo.stopDiscovery()
            emitLog("requestNetwork STA (diálogo sistema si aplica)")
            staRepo.connectIfSupported(best.record)

            val connected = withTimeoutOrNull(config.bootstrapTimeoutMs) {
                staRepo.staState.first { it is StaState.Connected || it is StaState.Error }
            }
            when (connected) {
                is StaState.Connected -> {
                    parentNode = TopologyNode(
                        nodeId = best.record.nid,
                        role = best.record.role,
                        hop = best.record.hop,
                        goSsid = best.record.goSsid,
                    )
                    publishTopology()
                    emitLog("STA OK ssid=${connected.ssid}")
                    startHelloSender()
                    if (config.autoActivateGoAfterSta) {
                        activateAsMember()
                    } else {
                        setAction("STA OK; auto GO desactivado — queda en JOINING")
                        setPhase(NodePhase.JOINING)
                    }
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
            emitLog("Sin candidatos PCT; este nodo será ROOT")
            startAsRoot()
        }
    }

    private suspend fun activateAsMember() {
        val goRepo = go ?: return
        role = "BRIDGE"
        setPhase(NodePhase.MEMBER)
        publishTopology()
        setAction("MEMBER: createGroup() BRIDGE (STA ya al padre) → anunciar")

        // Si el GO ya quedó Ready (p. ej. tras un Error transitorio), no recrear
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
                // Último intento: refrescar por si el GO ya existía
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

    /** Espera Ready o Error terminal de createGroup. */
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
        helloHub?.startListening()
        emitLog("HELLO listen :${config.ctrlPort}")
        // BRIDGE: re-enviar HELLO con hop/role definitivos
        if (role == "BRIDGE") {
            startHelloSender()
        }
        publishTopology()
        setPhase(if (role == "ROOT") NodePhase.ROOT else NodePhase.MEMBER)
        setAction(
            if (role == "ROOT") {
                "ROOT operativo: GO + anuncio. Esperando hijos."
            } else {
                "MEMBER operativo: STA al padre + GO propio + anuncio."
            },
        )
    }

    private fun startHelloSender() {
        val hub = helloHub ?: return
        val staRepo = sta ?: return
        val hop = when {
            parentNode != null -> parentNode!!.hop + 1
            else -> 0
        }
        val payload = HelloPayload(
            nid = nodeId,
            role = if (role == "ISLAND") "CHILD" else role,
            hop = hop,
        )
        hub.startSending(staRepo.activeNetwork, payload)
        emitLog("HELLO send → padre nid=${nodeId.take(8)}… hop=$hop")
    }

    private fun publishTopology() {
        val peers = dns?.parentCandidates?.value.orEmpty().map { c ->
            TopologyNode(
                nodeId = c.record.nid,
                role = c.record.role,
                hop = c.record.hop,
                goSsid = c.record.goSsid,
            )
        }
        val hop = when {
            role == "ROOT" -> 0
            parentNode != null -> parentNode!!.hop + 1
            else -> 0
        }
        val goSsid = (go?.goState?.value as? GoState.Ready)?.ssid
        // Preferir UUID PCT (HELLO); SoftAP MAC solo si aún no llegó HELLO
        val children = if (helloChildNodes.isNotEmpty()) {
            helloChildNodes.values.toList()
        } else {
            macChildNodes.map { it.copy(hop = hop + 1) }
        }
        val snapshot = TopologySnapshot(
            self = TopologyNode(
                nodeId = nodeId.ifBlank { "pending" },
                role = role,
                hop = hop,
                goSsid = goSsid,
            ),
            parent = parentNode,
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
        _events.tryEmit(PctEvent.Log(message))
    }

    private fun emitError(message: String) {
        _events.tryEmit(PctEvent.Error(message))
    }
}
