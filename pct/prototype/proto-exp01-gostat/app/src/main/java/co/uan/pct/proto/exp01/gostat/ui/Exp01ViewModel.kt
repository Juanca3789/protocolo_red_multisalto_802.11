package co.uan.pct.proto.exp01.gostat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.uan.pct.proto.exp01.gostat.data.p2p.DnsSdRepository
import co.uan.pct.proto.exp01.gostat.data.p2p.GoRepository
import co.uan.pct.proto.exp01.gostat.data.p2p.model.GoState
import co.uan.pct.proto.exp01.gostat.data.sta.LegacyStaRepository
import co.uan.pct.proto.exp01.gostat.data.sta.StaState
import co.uan.pct.proto.exp01.gostat.ui.model.Exp01UiState
import co.uan.pct.proto.exp01.gostat.ui.model.NodePhase
import co.uan.pct.proto.exp01.gostat.util.ParentSelector
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class Exp01ViewModel(
    private val goRepository: GoRepository,
    private val dnsSdRepository: DnsSdRepository,
    private val legacyStaRepository: LegacyStaRepository,
) : ViewModel() {

    private val nodeId = UUID.randomUUID().toString().replace("-", "")

    private val _uiState = MutableStateFlow(Exp01UiState())
    val uiState: StateFlow<Exp01UiState> = _uiState.asStateFlow()

    private var pendingAdvertiseRole: String? = null
    private var wantAdvertise = false
    private var wantDiscover = false

    init {
        appendLog("node_id=$nodeId")
        dnsSdRepository.setLocalNodeId(nodeId)
        observeRepositories()
    }

    private fun observeRepositories() {
        viewModelScope.launch {
            goRepository.goState.collect { go ->
                _uiState.update { it.copy(goState = go) }
                logGoState(go)
                if (go is GoState.Ready) {
                    if (pendingAdvertiseRole != null || wantAdvertise) {
                        val role = pendingAdvertiseRole ?: memberRole()
                        pendingAdvertiseRole = null
                        advertiseMember(role, go)
                    }
                }
                if ((go is GoState.Ready || go is GoState.Idle) &&
                    wantDiscover &&
                    !_uiState.value.isDiscovering
                ) {
                    dnsSdRepository.startDiscovery(useBroadFilter = _uiState.value.broadDiscovery)
                }
                refreshPhase()
            }
        }
        viewModelScope.launch {
            dnsSdRepository.parentCandidates.collect { candidates ->
                _uiState.update { it.copy(parentCandidates = candidates) }
                refreshPhase()
            }
        }
        viewModelScope.launch {
            dnsSdRepository.selectedParent.collect { selected ->
                _uiState.update {
                    it.copy(
                        selectedParentAddress = selected?.deviceAddress,
                        discoveredService = selected?.record ?: it.discoveredService,
                    )
                }
            }
        }
        viewModelScope.launch {
            dnsSdRepository.discovered.collect { record ->
                if (dnsSdRepository.selectedParent.value == null) {
                    _uiState.update { it.copy(discoveredService = record) }
                }
            }
        }
        viewModelScope.launch {
            goRepository.events.collect { event ->
                appendLog("GO: $event")
            }
        }
        viewModelScope.launch {
            dnsSdRepository.isAdvertising.collect { advertising ->
                _uiState.update { it.copy(isAdvertising = advertising) }
                refreshPhase()
            }
        }
        viewModelScope.launch {
            dnsSdRepository.isDiscovering.collect { discovering ->
                _uiState.update { it.copy(isDiscovering = discovering) }
                refreshPhase()
            }
        }
        viewModelScope.launch {
            dnsSdRepository.diagnostics.collect { diag ->
                _uiState.update { it.copy(dnsSdDiagnostics = diag) }
            }
        }
        viewModelScope.launch {
            dnsSdRepository.events.collect { event ->
                appendLog("DNS-SD: $event")
            }
        }
        viewModelScope.launch {
            legacyStaRepository.staState.collect { sta ->
                val previous = _uiState.value.staState
                _uiState.update { it.copy(staState = sta) }
                logStaState(sta)
                if (sta is StaState.Connected && previous !is StaState.Connected) {
                    onUpstreamConnected(sta.ssid)
                }
                refreshPhase()
            }
        }
    }

    fun setGoEnabled(on: Boolean) {
        _uiState.update { it.copy(goWanted = on) }
        if (on) {
            appendLog("GO ON")
            goRepository.createGroup()
        } else {
            appendLog("GO OFF")
            pendingAdvertiseRole = null
            wantAdvertise = false
            _uiState.update { it.copy(advertiseWanted = false) }
            dnsSdRepository.stopAdvertising()
            goRepository.removeGroup()
        }
    }

    fun setAdvertisingEnabled(on: Boolean) {
        wantAdvertise = on
        _uiState.update { it.copy(advertiseWanted = on) }
        if (!on) {
            appendLog("Anuncio OFF")
            dnsSdRepository.stopAdvertising()
            pendingAdvertiseRole = null
            return
        }
        appendLog("Anuncio ON")
        val go = goRepository.goState.value
        if (go is GoState.Ready) {
            advertiseMember(memberRole(), go)
        } else {
            pendingAdvertiseRole = memberRole()
            _uiState.update { it.copy(goWanted = true) }
            if (go !is GoState.Creating) {
                appendLog("Anuncio espera al GO; creando GO")
                goRepository.createGroup()
            }
        }
    }

    fun setDiscoveryEnabled(on: Boolean) {
        wantDiscover = on
        _uiState.update { it.copy(discoverWanted = on) }
        if (on) {
            appendLog("Búsqueda ON (no toca GO ni anuncio)")
            dnsSdRepository.startDiscovery(useBroadFilter = _uiState.value.broadDiscovery)
        } else {
            appendLog("Búsqueda OFF")
            dnsSdRepository.stopDiscovery()
        }
    }

    fun setStaEnabled(on: Boolean) {
        _uiState.update { it.copy(staWanted = on) }
        if (on) connectToParent() else disconnectSta()
    }

    fun setAutoActivateAfterJoin(enabled: Boolean) {
        _uiState.update { it.copy(autoActivateAfterJoin = enabled) }
        appendLog(if (enabled) "Auto-activar GO tras STA: ON" else "Auto-activar GO tras STA: OFF")
    }

    fun setBroadDiscovery(enabled: Boolean) {
        _uiState.update { it.copy(broadDiscovery = enabled) }
        appendLog(
            if (enabled) "Diagnóstico: filtro amplio ON" else "Diagnóstico: filtro amplio OFF",
        )
    }

    /** Primer nodo de la red: GO + anuncio ROOT sin padre. */
    fun startAsRoot() {
        appendLog("Iniciando como raíz (sin padre upstream)…")
        pendingAdvertiseRole = "ROOT"
        if (goRepository.goState.value is GoState.Ready) {
            advertiseMember("ROOT", goRepository.goState.value as GoState.Ready)
        } else {
            goRepository.createGroup()
        }
    }

    /** ISLAND: busca padres sin GO propio (evita tormenta P2P). */
    fun scanForParents() {
        val sta = _uiState.value.staState
        if (sta is StaState.Connected) {
            appendLog("STA sigue a ${sta.ssid}; igual escaneo sin GO")
        }
        if (goRepository.goState.value is GoState.Ready) {
            appendLog("Apagando GO local para escanear padres…")
            stopGo()
        }
        appendLog("Escaneo de padres (sin GO)…")
        _uiState.update { it.copy(phase = NodePhase.SCANNING) }
        dnsSdRepository.startDiscovery(useBroadFilter = _uiState.value.broadDiscovery)
    }

    /** Celda #2: GO intacto; baja el anuncio y lee TXT. */
    fun scanKeepingGo() {
        val go = goRepository.goState.value
        if (go !is GoState.Ready) {
            appendLog("GO no está listo; igual escaneo (sin quitar grupo)")
        }
        if (_uiState.value.isAdvertising) {
            appendLog("Parando anuncio (GO no se toca)…")
            dnsSdRepository.stopAdvertising()
        }
        appendLog("Escaneo con GO intacto, anuncio abajo…")
        _uiState.update { it.copy(phase = NodePhase.SCANNING) }
        dnsSdRepository.startDiscovery(useBroadFilter = _uiState.value.broadDiscovery)
    }

    fun selectParent(deviceAddress: String) {
        dnsSdRepository.selectParent(deviceAddress)
        val name = _uiState.value.parentCandidates
            .firstOrNull { it.deviceAddress == deviceAddress }
            ?.deviceName
        appendLog("Seleccionado padre: $name")
        refreshPhase()
    }

    /** Una sola solicitud STA al padre elegido (o al mejor candidato). */
    fun connectToParent() {
        val selected = dnsSdRepository.selectedParent.value
            ?: ParentSelector.best(_uiState.value.parentCandidates, nodeId)?.also { best ->
                dnsSdRepository.selectParent(best.deviceAddress)
                appendLog("Auto-selección del mejor candidato: ${best.deviceName}")
            }
        val record = selected?.record ?: dnsSdRepository.discovered.value
        if (record == null) {
            appendLog("✗ No hay padre seleccionado ni candidatos")
            return
        }
        if (record.goPsk.isBlank()) {
            appendLog("✗ PSK vacío en candidato")
            return
        }
        appendLog("Conectando STA a ${record.goSsid}…")
        _uiState.update { it.copy(phase = NodePhase.JOINING) }
        legacyStaRepository.connectIfSupported(record)
    }

    /** Tras STA: GO propio + anuncio BRIDGE. */
    fun activateAsMember() {
        val sta = _uiState.value.staState
        if (sta !is StaState.Connected) {
            appendLog("✗ Necesitas STA conectado antes de activar tu GO")
            return
        }
        pendingAdvertiseRole = "BRIDGE"
        if (goRepository.goState.value is GoState.Ready) {
            advertiseMember("BRIDGE", goRepository.goState.value as GoState.Ready)
        } else {
            appendLog("Creando GO propio (miembro)…")
            goRepository.createGroup()
        }
    }

    fun createGo() = goRepository.createGroup()

    fun stopGo() {
        if (_uiState.value.isAdvertising) dnsSdRepository.stopAdvertising()
        if (_uiState.value.isDiscovering) dnsSdRepository.stopDiscovery()
        goRepository.removeGroup()
        pendingAdvertiseRole = null
    }

    fun refreshGroupInfo() = goRepository.requestGroupInfo()

    fun stopAdvertising() = dnsSdRepository.stopAdvertising()

    fun reAdvertise() {
        val ready = goRepository.goState.value as? GoState.Ready ?: run {
            appendLog("✗ GO no listo")
            return
        }
        val role = memberRole()
        appendLog("Re-anunciando como $role…")
        dnsSdRepository.reAdvertiseCtrl(nodeId, ready.ssid, ready.psk, role)
    }

    fun disconnectSta() {
        legacyStaRepository.disconnect()
        dnsSdRepository.clearParentSelection()
        appendLog("STA desconectado; fase ISLAND")
        refreshPhase()
    }

    fun stopDiscovery() {
        dnsSdRepository.stopDiscovery()
        appendLog("Escaneo detenido")
        refreshPhase()
    }

    private fun onUpstreamConnected(upstreamSsid: String) {
        appendLog("✓ Upstream STA: $upstreamSsid")
        refreshPhase()
    }

    private fun advertiseMember(role: String, ready: GoState.Ready) {
        if (!ready.isGroupOwner) {
            appendLog("✗ No eres GO")
            return
        }
        dnsSdRepository.advertiseCtrl(
            nid = nodeId,
            goSsid = ready.ssid,
            goPsk = ready.psk,
            role = role,
        )
        appendLog("Anunciando _pct-ctrl como $role")
        refreshPhase()
    }

    private fun memberRole(): String =
        if (_uiState.value.staState is StaState.Connected) "BRIDGE" else "ROOT"

    private fun refreshPhase() {
        val phase = computePhase()
        _uiState.update { it.copy(phase = phase) }
    }

    private fun computePhase(): NodePhase {
        val state = _uiState.value
        return when {
            state.isDiscovering -> NodePhase.SCANNING
            state.staState is StaState.Connecting -> NodePhase.JOINING
            state.staState is StaState.Connected && state.goState is GoState.Ready ->
                NodePhase.MEMBER
            state.staState is StaState.Connected -> NodePhase.JOINING
            state.goState is GoState.Ready && state.isAdvertising &&
                state.staState !is StaState.Connected -> NodePhase.ROOT
            else -> NodePhase.ISLAND
        }
    }

    private fun logGoState(go: GoState) {
        val message = when (go) {
            GoState.Idle -> "GO: idle"
            GoState.Creating -> "GO: creando…"
            is GoState.Ready -> {
                val psk = if (go.psk.isBlank()) " psk=VACÍO" else ""
                "GO listo: ssid=${go.ssid} owner=${go.isGroupOwner}$psk"
            }
            is GoState.Error -> "GO error: ${go.message}"
        }
        appendLog(message)
    }

    private fun logStaState(sta: StaState) {
        when (sta) {
            StaState.Idle -> appendLog("STA: idle")
            StaState.Connecting -> appendLog("STA: conectando…")
            is StaState.Connected -> appendLog("STA: conectado a ${sta.ssid}")
            is StaState.Error -> appendLog("STA error: ${sta.message}")
        }
    }

    private fun appendLog(message: String) {
        _uiState.update { state ->
            state.copy(
                logs = (state.logs + "[${System.currentTimeMillis() % 100_000}] $message").takeLast(120),
            )
        }
    }

    override fun onCleared() {
        goRepository.close()
        dnsSdRepository.close()
        legacyStaRepository.disconnect()
        super.onCleared()
    }
}
