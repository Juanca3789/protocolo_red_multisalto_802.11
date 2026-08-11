package co.uan.pct.lib.core.internal.p2p

import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Handler
import android.os.Looper
import co.uan.pct.lib.core.internal.p2p.model.DiscoveryPhase
import co.uan.pct.lib.core.internal.p2p.model.DnsSdDiagnostics
import co.uan.pct.lib.core.internal.p2p.model.PctCtrlCandidate
import co.uan.pct.lib.core.internal.p2p.model.PctCtrlRecord
import co.uan.pct.lib.core.internal.util.ParentSelector
import co.uan.pct.lib.core.internal.util.PctCtrlRecordFactory
import co.uan.pct.lib.core.internal.util.PctInstanceCodec
import co.uan.pct.lib.core.internal.util.PctTxtParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class DnsSdRepository(
    private val p2p: P2pChannelHolder,
) : P2pEventListener {

    companion object {
        private const val SERVICE_TYPE = "_pct-ctrl._tcp"
        private const val DEFAULT_CTRL_PORT = 8765
        private const val T_DISCOVER_MS = 5_000L
        private const val T_PEER_WAIT_MS = 4_000L
        private const val T_TXT_RETRY_MS = 800L
        private const val MAX_TXT_RETRIES = 8
        private const val T_CANDIDATE_SETTLE_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _discovered = MutableStateFlow<PctCtrlRecord?>(null)
    val discovered: StateFlow<PctCtrlRecord?> = _discovered.asStateFlow()

    private val _parentCandidates = MutableStateFlow<List<PctCtrlCandidate>>(emptyList())
    val parentCandidates: StateFlow<List<PctCtrlCandidate>> = _parentCandidates.asStateFlow()

    private val _selectedParent = MutableStateFlow<PctCtrlCandidate?>(null)
    val selectedParent: StateFlow<PctCtrlCandidate?> = _selectedParent.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _diagnostics = MutableStateFlow(DnsSdDiagnostics())
    val diagnostics: StateFlow<DnsSdDiagnostics> = _diagnostics.asStateFlow()

    private val _discoveryFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val discoveryFinished: SharedFlow<Unit> = _discoveryFinished.asSharedFlow()

    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var localService: WifiP2pDnsSdServiceInfo? = null

    private var discoveryActive = false
    private var serviceRequestAdded = false
    private var broadFilter = false
    private var settleMs: Long = T_CANDIDATE_SETTLE_MS
    private var servicesSeen = 0
    private var pctCtrlSeen = 0
    private var discoveryTicks = 0
    private var txtCallbacks = 0
    private var txtRetriesAfterService = 0
    private var lastPctCtrlInstance: String? = null
    private var localNodeId: String? = null
    private var discoveryLocked = false
    private val candidateMap = linkedMapOf<String, PctCtrlCandidate>()

    private val peerWaitTimeout = Runnable { onPeerWaitTimeout() }
    private val rediscoverTick = Runnable { onRediscoverTick() }
    private val txtRetryRunnable = Runnable { retryTxtResolution() }
    private val settleRunnable = Runnable { onDiscoverySettle() }

    fun setLocalNodeId(nodeId: String) {
        localNodeId = nodeId
    }

    fun selectParent(deviceAddress: String) {
        val candidate = candidateMap[deviceAddress] ?: return
        _selectedParent.value = candidate
        _discovered.value = candidate.record
        emitEvent(
            "Padre seleccionado: ${candidate.deviceName} " +
                "nid=${candidate.record.nid.take(8)}… hop=${candidate.record.hop}",
        )
        lockDiscovery("Selección manual; deteniendo escaneo")
    }

    fun clearParentSelection() {
        _selectedParent.value = null
        _discovered.value = ParentSelector.best(_parentCandidates.value, localNodeId)?.record
    }

    private val txtListener = WifiP2pManager.DnsSdTxtRecordListener { fullDomain, record, device ->
        txtCallbacks++
        val rawPreview = if (record.isEmpty()) "∅" else recordPreview(record)
        emitEvent(
            "TXT callback #$txtCallbacks de ${device.deviceName}: " +
                "dominio=$fullDomain raw=$rawPreview",
        )
        updateDiagnostics { it.copy(txtCallbacks = txtCallbacks) }

        val parsed = PctTxtParser.parseCtrlRecord(record, device.deviceAddress)
        if (parsed != null) {
            txtRetriesAfterService = 0
            mainHandler.removeCallbacks(txtRetryRunnable)
            addCandidate(
                record = parsed,
                deviceName = device.deviceName,
                deviceAddress = device.deviceAddress,
                instanceName = fullDomain,
                source = "TXT",
            )
        } else {
            val reason = PctTxtParser.describeParseFailure(record)
            emitEvent(
                "TXT _pct-ctrl RECHAZADO de ${device.deviceName}: $reason " +
                    "normalizado=${recordPreview(PctTxtParser.normalizeTxtRecord(record))}",
            )
            updateDiagnostics {
                it.copy(
                    txtCallbacks = txtCallbacks,
                    hint = "Service found pero TXT inválido: $reason",
                )
            }
            scheduleTxtRetry()
        }
    }

    private val serviceListener = WifiP2pManager.DnsSdServiceResponseListener {
            instanceName,
            registrationType,
            device,
        ->
        servicesSeen++
        val isPctCtrl = PctTxtParser.isPctCtrlService(registrationType)
        if (isPctCtrl) {
            pctCtrlSeen++
            lastPctCtrlInstance = instanceName
            txtRetriesAfterService = 0
            updateDiagnostics(phase = DiscoveryPhase.ServiceFound) {
                it.copy(
                    servicesSeen = servicesSeen,
                    pctCtrlSeen = pctCtrlSeen,
                    hint = "Service found; esperando TXT (reintento automático)…",
                )
            }
            emitEvent(
                "Service found _pct-ctrl: $instanceName tipo=$registrationType " +
                    "de ${device.deviceName} (${device.deviceAddress})",
            )
            tryApplyInstancePayload(instanceName, device.deviceAddress, device.deviceName)
            scheduleTxtRetry(immediate = true)
        } else {
            updateDiagnostics {
                it.copy(servicesSeen = servicesSeen)
            }
            emitEvent(
                "Servicio ajeno #$servicesSeen: $instanceName tipo=$registrationType " +
                    "de ${device.deviceName} (no es _pct-ctrl)",
            )
        }
    }

    init {
        p2p.addListener(this)
        p2p.manager.setDnsSdResponseListeners(p2p.channel, serviceListener, txtListener)
    }

    fun reAdvertiseCtrl(
        nid: String,
        goSsid: String,
        goPsk: String,
        role: String = "ROOT",
    ) {
        emitEvent("Re-anunciando: limpiando servicios locales…")
        p2p.manager.clearLocalServices(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                localService = null
                _isAdvertising.value = false
                advertiseCtrl(nid, goSsid, goPsk, role)
            }

            override fun onFailure(reason: Int) {
                emitEvent("clearLocalServices (re-anuncio) falló: ${P2pFailureReasons.describe(reason)}")
                advertiseCtrl(nid, goSsid, goPsk, role)
            }
        })
    }

    fun advertiseCtrl(
        nid: String,
        goSsid: String,
        goPsk: String,
        role: String = "ROOT",
    ) {
        if (goSsid.isBlank()) {
            emitEvent("FALLO anuncio: go_ssid vacío — pulsa Info GO")
            return
        }
        if (goPsk.isBlank()) {
            emitEvent(
                "FALLO anuncio: go_psk vacío — Android no devolvió passphrase; " +
                    "pulsa Info GO o recrea el grupo",
            )
            return
        }
        when (val encoded = PctInstanceCodec.encode(nid, goSsid, goPsk)) {
            is PctInstanceCodec.EncodeResult.Failure -> {
                emitEvent("FALLO anuncio: ${encoded.reason}")
                return
            }
            is PctInstanceCodec.EncodeResult.Success -> {
                emitEvent(
                    "Instance DNS-SD (${encoded.instanceName.length} chars, " +
                        "${encoded.rawBytes} B payload): ${encoded.instanceName}",
                )
                publishAdvertise(encoded.instanceName, nid, goSsid, goPsk, role)
            }
        }
    }

    private fun publishAdvertise(
        instanceName: String,
        nid: String,
        goSsid: String,
        goPsk: String,
        role: String,
    ) {
        emitEvent("Preparando anuncio $SERVICE_TYPE…")
        val record = PctTxtParser.buildCtrlTxtRecordCompact(
            nid = nid,
            goSsid = goSsid,
            goPsk = goPsk,
            ctrlPort = DEFAULT_CTRL_PORT,
        )
        emitEvent("TXT compacto (respaldo): ${recordPreview(record)} (${estimateTxtBytes(record)} B)")
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            instanceName,
            SERVICE_TYPE,
            record,
        )
        localService = serviceInfo
        p2p.manager.addLocalService(p2p.channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isAdvertising.value = true
                emitEvent(
                    "Anuncio activo: $SERVICE_TYPE instance=$instanceName " +
                        "ssid=$goSsid psk_len=${goPsk.length} puerto=$DEFAULT_CTRL_PORT",
                )
                emitEvent(
                    "Joiner puede leer credenciales del instance (TXT opcional en este dispositivo)",
                )
            }

            override fun onFailure(reason: Int) {
                localService = null
                emitEvent("addLocalService falló: ${P2pFailureReasons.describe(reason)}")
            }
        })
    }

    fun stopAdvertising() {
        if (!_isAdvertising.value && localService == null) {
            emitEvent("Sin anuncio activo que detener")
            return
        }
        p2p.manager.clearLocalServices(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                localService = null
                _isAdvertising.value = false
                emitEvent("Anuncio $SERVICE_TYPE detenido")
            }

            override fun onFailure(reason: Int) {
                emitEvent("clearLocalServices falló: ${P2pFailureReasons.describe(reason)}")
            }
        })
    }

    fun startDiscovery(
        useBroadFilter: Boolean = false,
        candidateSettleMs: Long = T_CANDIDATE_SETTLE_MS,
    ) {
        stopDiscoveryHandlers()
        discoveryActive = true
        discoveryLocked = false
        serviceRequestAdded = false
        broadFilter = useBroadFilter
        settleMs = candidateSettleMs.coerceAtLeast(1_000L)
        servicesSeen = 0
        pctCtrlSeen = 0
        discoveryTicks = 0
        txtCallbacks = 0
        txtRetriesAfterService = 0
        lastPctCtrlInstance = null
        candidateMap.clear()
        _parentCandidates.value = emptyList()
        _selectedParent.value = null
        _discovered.value = null
        mainHandler.removeCallbacks(txtRetryRunnable)
        mainHandler.removeCallbacks(settleRunnable)
        _isDiscovering.value = true

        val filterLabel = if (useBroadFilter) "TODOS (diagnóstico)" else SERVICE_TYPE
        updateDiagnostics(phase = DiscoveryPhase.DiscoveringPeers) {
            DnsSdDiagnostics(
                phase = DiscoveryPhase.DiscoveringPeers,
                broadFilter = useBroadFilter,
                hint = "Paso 1/3: discoverPeers → esperar peers → discoverServices ($filterLabel)",
            )
        }
        emitEvent("Iniciando búsqueda (filtro=$filterLabel)…")
        emitEvent("Paso 1: discoverPeers()…")
        p2p.manager.discoverPeers(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                emitEvent("discoverPeers OK; esperando peers (máx ${T_PEER_WAIT_MS}ms)…")
                updateDiagnostics(phase = DiscoveryPhase.WaitingPeers) {
                    it.copy(hint = "Paso 2/3: esperando PEERS_CHANGED con ≥1 peer")
                }
                mainHandler.postDelayed(peerWaitTimeout, T_PEER_WAIT_MS)
            }

            override fun onFailure(reason: Int) {
                failDiscovery("discoverPeers falló: ${P2pFailureReasons.describe(reason)}")
            }
        })
    }

    fun stopDiscovery() {
        discoveryLocked = true
        discoveryActive = false
        stopDiscoveryHandlers()
        serviceRequest?.let { request ->
            p2p.manager.removeServiceRequest(p2p.channel, request, null)
            serviceRequest = null
        }
        serviceRequestAdded = false
        p2p.manager.stopPeerDiscovery(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isDiscovering.value = false
                updateDiagnostics(phase = DiscoveryPhase.Idle) {
                    DnsSdDiagnostics(hint = "Búsqueda detenida")
                }
                emitEvent(
                    "Descubrimiento detenido " +
                        "(peers=${_diagnostics.value.peerCount} " +
                        "srv=${servicesSeen} pct=${pctCtrlSeen} ticks=$discoveryTicks)",
                )
            }

            override fun onFailure(reason: Int) {
                _isDiscovering.value = false
                emitEvent("stopPeerDiscovery falló: ${P2pFailureReasons.describe(reason)}")
            }
        })
    }

    override fun onP2pIntent(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
                val started = state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                val label = if (started) "STARTED" else "STOPPED"
                emitEvent("Broadcast P2P scan: $label (sesión activa=$discoveryActive)")
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                p2p.manager.requestPeers(p2p.channel) { peers ->
                    val count = peers.deviceList.size
                    val names = peers.deviceList.joinToString { "${it.deviceName}(${it.deviceAddress})" }
                    updateDiagnostics { it.copy(peerCount = count) }
                    emitEvent(
                        if (count == 0) {
                            "Peers visibles: 0 — acerca los teléfonos / revisa Wi‑Fi Direct"
                        } else {
                            "Peers visibles: $count → $names"
                        },
                    )
                    if (discoveryActive && !serviceRequestAdded && count > 0) {
                        mainHandler.removeCallbacks(peerWaitTimeout)
                        emitEvent("Peer detectado; lanzando discoverServices sin esperar timeout")
                        beginServiceDiscovery()
                    }
                }
            }
        }
    }

    fun close() {
        discoveryActive = false
        stopDiscoveryHandlers()
        serviceRequest?.let { request ->
            p2p.manager.removeServiceRequest(p2p.channel, request, null)
        }
        if (_isAdvertising.value) {
            stopAdvertising()
        }
        p2p.removeListener(this)
    }

    private fun onPeerWaitTimeout() {
        if (!discoveryActive || serviceRequestAdded) return
        val peers = _diagnostics.value.peerCount
        emitEvent(
            "Timeout peers (${T_PEER_WAIT_MS}ms): $peers visibles; " +
                "lanzando discoverServices de todos modos",
        )
        beginServiceDiscovery()
    }

    private fun beginServiceDiscovery() {
        if (!discoveryActive || serviceRequestAdded) return
        serviceRequestAdded = true
        updateDiagnostics(phase = DiscoveryPhase.RegisteringRequest) {
            it.copy(hint = "Paso 3/3: addServiceRequest + discoverServices")
        }
        val request = if (broadFilter) {
            WifiP2pDnsSdServiceRequest.newInstance()
        } else {
            WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        }
        serviceRequest = request
        val filterLabel = if (broadFilter) "TODOS" else SERVICE_TYPE
        emitEvent("addServiceRequest (filtro=$filterLabel)…")
        p2p.manager.addServiceRequest(
            p2p.channel,
            request,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    emitEvent("addServiceRequest OK")
                    runDiscoverServices(isRetry = false)
                }

                override fun onFailure(reason: Int) {
                    serviceRequestAdded = false
                    failDiscovery("addServiceRequest falló: ${P2pFailureReasons.describe(reason)}")
                }
            },
        )
    }

    private fun runDiscoverServices(isRetry: Boolean) {
        if (!discoveryActive) return
        updateDiagnostics(phase = DiscoveryPhase.DiscoveringServices) {
            it.copy(
                hint = if (isRetry) {
                    "Reintento #$discoveryTicks; peers=${it.peerCount} srv_pct=${it.pctCtrlSeen}"
                } else {
                    "Escuchando _pct-ctrl (reintento cada ${T_DISCOVER_MS}ms)"
                },
            )
        }
        val label = if (isRetry) "rediscover #$discoveryTicks" else "discoverServices"
        p2p.manager.discoverServices(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                emitEvent("$label OK (peers=${_diagnostics.value.peerCount} srv_vistos=$servicesSeen)")
                scheduleRediscoverTick()
            }

            override fun onFailure(reason: Int) {
                emitEvent("$label falló: ${P2pFailureReasons.describe(reason)}")
                if (discoveryActive) {
                    scheduleRediscoverTick()
                }
            }
        })
    }

    private fun onRediscoverTick() {
        if (!discoveryActive || discoveryLocked) return
        discoveryTicks++
        updateDiagnostics { it.copy(discoveryTicks = discoveryTicks) }
        if (_discovered.value != null) {
            emitEvent("Tick #$discoveryTicks: servicio ya listo; sigue escuchando")
            scheduleRediscoverTick()
            return
        }
        if (pctCtrlSeen > 0) {
            emitEvent(
                "Tick #$discoveryTicks: service found (TXT cb=$txtCallbacks); " +
                    "reintentando discoverServices",
            )
            runDiscoverServices(isRetry = true)
            return
        }
        val peers = _diagnostics.value.peerCount
        val hint = when {
            peers == 0 -> "Sin peers: acerca teléfonos o prueba Joiner sin GO"
            peers > 0 && servicesSeen == 0 ->
                "Hay peers pero 0 servicios: ¿Host anunció? ¿Dos GO activos?"
            peers > 0 && servicesSeen > 0 ->
                "Hay servicios ajenos pero no _pct-ctrl; prueba filtro amplio"
            else -> "Reintentando discoverServices…"
        }
        updateDiagnostics { it.copy(hint = hint) }
        emitEvent("Tick #$discoveryTicks: $hint")
        runDiscoverServices(isRetry = true)
    }

    private fun scheduleRediscoverTick() {
        if (!discoveryActive) return
        mainHandler.removeCallbacks(rediscoverTick)
        mainHandler.postDelayed(rediscoverTick, T_DISCOVER_MS)
    }

    private fun stopDiscoveryHandlers() {
        mainHandler.removeCallbacks(peerWaitTimeout)
        mainHandler.removeCallbacks(rediscoverTick)
        mainHandler.removeCallbacks(txtRetryRunnable)
        mainHandler.removeCallbacks(settleRunnable)
    }

    private fun onDiscoverySettle() {
        if (!discoveryActive || discoveryLocked) return
        val count = _parentCandidates.value.size
        emitEvent(
            "Ventana de escaneo cerrada (${settleMs}ms): " +
                "$count padre(s) candidato(s)",
        )
        updateDiagnostics {
            it.copy(
                hint = if (count == 0) {
                    "Ningún padre usable; bootstrap puede iniciar raíz"
                } else {
                    "Candidatos listos para auto-join (1 STA)"
                },
            )
        }
        lockDiscovery("Escaneo finalizado", notifyFinished = true)
    }

    private fun lockDiscovery(reason: String, notifyFinished: Boolean = false) {
        if (discoveryLocked) return
        discoveryLocked = true
        mainHandler.removeCallbacks(rediscoverTick)
        mainHandler.removeCallbacks(txtRetryRunnable)
        mainHandler.removeCallbacks(settleRunnable)
        emitEvent("Escaneo bloqueado: $reason")
        if (discoveryActive) {
            discoveryActive = false
            _isDiscovering.value = false
            serviceRequest?.let { request ->
                p2p.manager.removeServiceRequest(p2p.channel, request, null)
            }
            p2p.manager.stopPeerDiscovery(p2p.channel, null)
        }
        if (notifyFinished) {
            _discoveryFinished.tryEmit(Unit)
        }
    }

    private fun scheduleCandidateSettle() {
        if (discoveryLocked) return
        mainHandler.removeCallbacks(settleRunnable)
        mainHandler.postDelayed(settleRunnable, settleMs)
    }

    private fun addCandidate(
        record: PctCtrlRecord,
        deviceName: String,
        deviceAddress: String,
        instanceName: String,
        source: String,
    ) {
        if (discoveryLocked) return
        val candidate = PctCtrlCandidate(
            record = record,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            instanceName = instanceName,
            discoveredAtMs = System.currentTimeMillis(),
        )
        if (ParentSelector.isSelf(candidate, localNodeId)) {
            emitEvent("Candidato ignorado (propio nodo) de $deviceName")
            return
        }
        val isNew = !candidateMap.containsKey(deviceAddress)
        candidateMap[deviceAddress] = candidate
        val ranked = ParentSelector.rank(candidateMap.values.toList(), localNodeId)
        _parentCandidates.value = ranked
        if (_selectedParent.value == null) {
            _discovered.value = ParentSelector.best(ranked, localNodeId)?.record
        }
        updateDiagnostics(phase = DiscoveryPhase.TxtReady) {
            it.copy(
                txtCallbacks = txtCallbacks,
                hint = "${ranked.size} candidato(s); selecciona padre",
            )
        }
        emitEvent(
            "Candidato [$source] #${ranked.size}: ${deviceName} " +
                "nid=${record.nid.take(8)}… hop=${record.hop} role=${record.role} " +
                "ssid=${record.goSsid}",
        )
        if (isNew && ranked.size == 1) {
            scheduleCandidateSettle()
        }
    }

    private fun scheduleTxtRetry(immediate: Boolean = false) {
        if (!discoveryActive || _discovered.value != null) return
        if (txtRetriesAfterService >= MAX_TXT_RETRIES) {
            updateDiagnostics {
                it.copy(
                    hint = "Service found pero 0 TXT tras $MAX_TXT_RETRIES reintentos; " +
                        "re-anuncia Host o TXT demasiado grande",
                )
            }
            emitEvent(
                "TXT no llegó tras $MAX_TXT_RETRIES reintentos " +
                    "(instance=$lastPctCtrlInstance callbacks=$txtCallbacks)",
            )
            return
        }
        mainHandler.removeCallbacks(txtRetryRunnable)
        val delay = if (immediate) 200L else T_TXT_RETRY_MS
        mainHandler.postDelayed(txtRetryRunnable, delay)
    }

    private fun retryTxtResolution() {
        if (!discoveryActive || discoveryLocked || _selectedParent.value != null) return
        txtRetriesAfterService++
        emitEvent(
            "Reintento TXT #$txtRetriesAfterService/$MAX_TXT_RETRIES " +
                "→ discoverServices() (instance=$lastPctCtrlInstance)",
        )
        p2p.manager.discoverServices(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                emitEvent("discoverServices (TXT retry #$txtRetriesAfterService) OK")
                scheduleTxtRetry()
            }

            override fun onFailure(reason: Int) {
                emitEvent(
                    "discoverServices (TXT retry) falló: ${P2pFailureReasons.describe(reason)}",
                )
                scheduleTxtRetry()
            }
        })
    }

    private fun failDiscovery(message: String) {
        updateDiagnostics(phase = DiscoveryPhase.Failed) {
            it.copy(hint = message)
        }
        emitEvent("FALLO búsqueda: $message")
    }

    private fun updateDiagnostics(
        phase: DiscoveryPhase? = null,
        transform: (DnsSdDiagnostics) -> DnsSdDiagnostics = { it },
    ) {
        _diagnostics.value = transform(
            _diagnostics.value.let { current ->
                if (phase != null) current.copy(phase = phase) else current
            },
        )
    }

    private fun tryApplyInstancePayload(instanceName: String, deviceAddress: String, deviceName: String) {
        val payload = PctInstanceCodec.decode(instanceName) ?: run {
            emitEvent(
                "Instance '$instanceName' sin credenciales embebidas " +
                    "(formato antiguo pct-*; depende de TXT)",
            )
            return
        }
        txtRetriesAfterService = 0
        mainHandler.removeCallbacks(txtRetryRunnable)
        val record = PctCtrlRecordFactory.fromInstancePayload(payload, deviceAddress)
        addCandidate(
            record = record,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            instanceName = instanceName,
            source = "instance",
        )
    }

    private fun emitEvent(message: String) {
        _lastEvent.value = message
        _events.tryEmit(message)
    }

    private fun recordPreview(record: Map<String, String>): String =
        record.entries.joinToString { "${it.key}=${it.value.take(12)}${if (it.value.length > 12) "…" else ""}" }

    private fun estimateTxtBytes(record: Map<String, String>): Int =
        record.entries.sumOf { it.key.length + it.value.length + 2 }
}
