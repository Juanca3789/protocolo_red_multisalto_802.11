package co.uan.pct.lib.core.physical

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import co.uan.pct.lib.core.link.Uplink
import co.uan.pct.lib.core.link.buildUplink
import androidx.annotation.RequiresPermission
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.random.Random

@OptIn(ExperimentalUuidApi::class)
class PhysicalLayer(
    val nodeConfig: NodeConfig,
    initialState: NodeState = NodeState.Init,
    context: Context,
    private val timeouts: PhysicalLayerTimeouts = PhysicalLayerTimeouts(),
) {
    val wifiP2pManager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    val wifiManager: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private val p2pRuntime = P2pRuntime(context, wifiP2pManager)
    private val staLink = StaLink(connectivityManager).also { link ->
        link.onLost = { onStaLost() }
    }
    private val dns = DnsSdService(wifiP2pManager)

    private val _nodeState = MutableStateFlow(initialState)
    val nodeState: StateFlow<NodeState> = _nodeState.asStateFlow()

    private val _snapshot = MutableStateFlow(PhysicalSnapshot(nodeId = nodeConfig.nodeId.pctHex()))
    val snapshot: StateFlow<PhysicalSnapshot> = _snapshot.asStateFlow()

    /** Lo único que L2 necesita de L1: hay STA al padre → aquí va a dónde abrir el TCP; null si no. */
    private val _uplink = MutableStateFlow<Uplink?>(null)
    val uplink: StateFlow<Uplink?> = _uplink.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    val scope get() = p2pRuntime.scope
    val channel: WifiP2pManager.Channel get() = p2pRuntime.channel

    var goSsid: String = ""
        private set
    var goPsk: String = ""
        private set
    private var activeStaNetwork: Network? = null

    /** BSSID del GO padre mientras hay STA; null si no estoy asociado. */
    private val staBssid: String? get() = staLink.bssid

    private var parent: ServiceStructure? = null
    private var childCount: Int = 0
    private var graph = RelativeGraph()
    private var started = false
    private var closed = false
    private var meshJob: Job? = null
    private var lastAdvertised: ServiceStructure? = null
    private var joining = false
    private val radioSeen = java.util.concurrent.ConcurrentHashMap<String, ServiceStructure>()

    private suspend fun <T> withP2p(block: suspend () -> T): T = p2pRuntime.withP2p(block)

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun start() {
        if (started || closed) return
        started = true
        meshJob = scope.launch {
            runCatching { runMesh() }.onFailure { log("red: ${it.message}") }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private suspend fun runMesh() {
        setState(NodeState.Starting)
        withP2p { limpiarRadioLocked("arranque") }
        publish(action = "busco red sin grupo propio", searching = true)
        log("arranque: ${timeouts.bootstrapSearchMs} ms de búsqueda sin grupo propio")
        val vistos = discoverParentsLocked(timeouts.bootstrapSearchMs)
        ingest(vistos)
        val padre = vistos.firstOrNull { it.goSsid.isNotBlank() && it.goPsk.isNotBlank() }
        if (padre != null) {
            log("vi anuncio ssid=${padre.goSsid}; me asocio")
            publish(action = "asociando al padre", searching = false)
            runCatching { connectToParent(padre) }
                .onFailure { log("asociación: ${it.message}") }
        }
        if (parent == null) {
            publish(action = "creo grupo propio", searching = false)
            log("nadie a la vista; me vuelvo raíz y anuncio")
            while (scope.isActive && !closed && goSsid.isBlank()) {
                runCatching { ensureGo() }.onFailure { log("grupo propio: ${it.message}") }
                if (goSsid.isBlank()) delay(1_500)
            }
        } else if (goSsid.isBlank()) {
            publish(action = "creo grupo propio", searching = false)
            runCatching { ensureGo() }.onFailure { log("grupo propio: ${it.message}") }
        }
        advertiseCurrent()
        setRunning()
        log("grupo y anuncio listos ssid=$goSsid — anuncio quieto; un pulso de ${timeouts.scanMs} ms al azar cada ${timeouts.pulsePeriodMs} ms")
        while (scope.isActive && !closed) {
            val inicioVentana = System.currentTimeMillis()
            val hueco = (timeouts.pulsePeriodMs - timeouts.scanMs).coerceAtLeast(1)
            val espera = Random.nextLong(hueco)
            log("próximo pulso en ${espera} ms (dura ${timeouts.scanMs} ms)")
            delay(espera)
            runCatching { searchTick() }
                .onFailure { log("búsqueda: ${it.message}") }
            val resto = timeouts.pulsePeriodMs - (System.currentTimeMillis() - inicioVentana)
            if (resto > 0) delay(resto)
        }
    }

    /** Quita búsqueda, anuncio, STA y grupo. Sigue aunque un paso falle. */
    private suspend fun limpiarRadioLocked(motivo: String) {
        log("$motivo: dejo de buscar, quito anuncio, suelto STA y tumbo el grupo")
        anyway { wifiP2pManager.awaitStopPeerDiscovery(channel) }
        anyway {
            wifiP2pManager.awaitP2pAction(channel) { ch, listener ->
                wifiP2pManager.cancelConnect(ch, listener)
            }
        }
        anyway {
            wifiP2pManager.awaitP2pAction(channel) { ch, listener ->
                dns.clearServiceRequests(ch, listener)
            }
        }
        anyway {
            wifiP2pManager.awaitP2pAction(channel) { ch, listener ->
                dns.stopDiscovery(ch, listener)
            }
        }
        anyway {
            wifiP2pManager.awaitP2pAction(channel) { ch, listener ->
                dns.clearLocalServices(ch, listener)
            }
        }
        staLink.disconnect()
        activeStaNetwork = null
        _uplink.value = null
        parent = null
        lastAdvertised = null
        repeat(4) { intento ->
            val grupo = wifiP2pManager.peekGroupInfo(channel)
            if (grupo == null) {
                goSsid = ""
                goPsk = ""
                log("$motivo: radio limpia")
                return
            }
            log("$motivo: tumbo grupo ssid=${grupo.networkName} (intento ${intento + 1})")
            anyway {
                wifiP2pManager.awaitP2pAction(channel) { ch, listener ->
                    wifiP2pManager.removeGroup(ch, listener)
                }
            }
            delay(300)
        }
        val queda = wifiP2pManager.peekGroupInfo(channel)
        goSsid = ""
        goPsk = ""
        if (queda != null) log("$motivo: el grupo ${queda.networkName} sigue vivo")
        else log("$motivo: radio limpia")
    }

    private suspend fun anyway(block: suspend () -> Unit) {
        runCatching { block() }.onFailure { log("radio: ${it.message}") }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private suspend fun searchTick() {
        if (joining) {
            publish(action = "asociación al padre en curso")
            return
        }
        if (goSsid.isNotBlank()) {
            advertiseCurrent()
        }
        publish(searching = true, action = "pulso de búsqueda")
        val candidatos = discoverParentsLocked(timeouts.scanMs)
        ingest(candidatos)
        val padre = candidatos.firstOrNull {
            it.goSsid.isNotBlank() && it.goPsk.isNotBlank()
        }
        if (parent == null && padre != null) {
            log("pulso: vi anuncio ssid=${padre.goSsid}; me asocio")
            runCatching { connectToParent(padre) }
                .onFailure { log("asociación: ${it.message}") }
        } else {
            log("pulso: ${candidatos.size} visibles")
        }
        // El anuncio local sobrevive a la búsqueda; solo se reemplaza si cambió (hijos, padre).
        refreshChildCount()
        advertiseCurrent()
        setRunning()
        publish(
            searching = false,
            action = if (parent == null) "solo anuncio (sin padre)" else "solo anuncio (con padre)",
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private suspend fun ensureGo() {
        if (goSsid.isNotBlank() && goPsk.isNotBlank()) return
        withP2p { startGoLocked() }
    }

    private suspend fun startGoLocked(): GoCredentials {
        val existing = wifiP2pManager.peekGroupInfo(channel)
        if (existing != null && existing.isGroupOwner) {
            val creds = existing.toGoCredentials()
            if (goSsid.isNotBlank() && existing.networkName == goSsid) {
                if (creds.psk.isNotBlank()) goPsk = creds.psk
                log("grupo propio ya activo ssid=$goSsid")
                return GoCredentials(goSsid, goPsk)
            }
            log("grupo residual ssid=${existing.networkName}; lo tumbo, no lo reutilizo")
            anyway {
                wifiP2pManager.awaitP2pAction(channel) { ch, listener ->
                    wifiP2pManager.removeGroup(ch, listener)
                }
            }
            goSsid = ""
            goPsk = ""
            delay(400)
        }
        return withTimeout(timeouts.goCreateMs) {
            var last: Throwable? = null
            val ssid = pctGoSsid()
            val psk = pctGoPsk()
            for (config in socialGoConfigs(ssid, psk)) {
                val created = runCatching {
                    wifiP2pManager.awaitP2pAction(
                        channel = channel,
                        busyRetries = timeouts.busyRetries,
                        busyRetryMs = timeouts.busyRetryMs,
                    ) { ch, listener ->
                        if (config == null) {
                            wifiP2pManager.createGroup(ch, listener)
                        } else {
                            wifiP2pManager.createGroup(ch, config, listener)
                        }
                    }
                }
                if (created.isSuccess) {
                    last = null
                    break
                }
                last = created.exceptionOrNull()
                log("crear grupo: ${last?.message}")
            }
            if (last != null) throw last
            val group = wifiP2pManager.awaitGroupInfo(
                channel = channel,
                maxAttempts = timeouts.groupInfoMaxAttempts,
                delayMs = timeouts.groupInfoRetryMs,
            )
            val creds = group.toGoCredentials()
            require(creds.ssid.isNotBlank() && creds.psk.isNotBlank()) {
                "grupo propio sin SSID/clave tras createGroup"
            }
            goSsid = creds.ssid
            goPsk = creds.psk
            log("grupo propio listo ${group.operatingMhz()} MHz ssid=$goSsid")
            creds
        }
    }

    private fun pctGoSsid(): String {
        val h = nodeConfig.nodeId.pctHex()
        return "DIRECT-${h.take(2)}-${h.substring(2, 8)}"
    }

    private fun pctGoPsk(): String = nodeConfig.nodeId.pctHex().take(16)

    private fun socialGoConfigs(ssid: String, psk: String): List<WifiP2pConfig?> {
        fun named(block: WifiP2pConfig.Builder.() -> WifiP2pConfig.Builder): WifiP2pConfig =
            WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(psk)
                .block()
                .build()
        return listOf(
            named { setGroupOperatingFrequency(2437) },
            named { setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) },
            null,
        )
    }

    private suspend fun discoverParentsLocked(esperaMs: Long): List<ServiceStructure> {
        // Ventana de escucha nueva: lo visto antes pudo apagarse. No arrastrar anuncios viejos.
        radioSeen.clear()
        dns.onTxtRecord = txt@{ txt, device ->
            val parsed = decodeDnsSdTxt(txt, device.deviceAddress) ?: run {
                log("anuncio ilegible claves=${txt.keys}")
                return@txt
            }
            if (parsed.nid.pctHex().take(8) == nodeConfig.nodeId.pctHex().take(8)) return@txt
            radioSeen[device.deviceAddress] = mergeSeen(radioSeen[device.deviceAddress], parsed)
            log("anuncio nid=${parsed.nid.pctHex().take(8)} profundidad=${parsed.depth} ssid=${parsed.goSsid}")
        }
        dns.onServiceFound = svc@{ instance, type, device ->
            log("servicio $type nombre=$instance de ${device.deviceAddress}")
            val parsed = decodeDnsSdInstance(instance, device.deviceAddress)
            if (parsed == null) {
                return@svc
            }
            if (parsed.nid.pctHex().take(8) == nodeConfig.nodeId.pctHex().take(8)) return@svc
            val previous = radioSeen[device.deviceAddress]
            if (previous == null || previous.goSsid.isBlank()) {
                radioSeen[device.deviceAddress] = mergeSeen(previous, parsed)
                log("instancia ssid=${parsed.goSsid} nid=${parsed.nid.pctHex().take(8)}")
            }
        }
        withContext(Dispatchers.Main) {
            dns.registerListeners(channel)
        }
        withP2p {
            runCatching {
                wifiP2pManager.awaitP2pAction(
                    channel,
                    busyRetries = timeouts.busyRetries,
                    busyRetryMs = timeouts.busyRetryMs,
                ) { ch, listener ->
                    wifiP2pManager.discoverPeers(ch, listener)
                }
            }.onFailure { log("pares de radio: ${it.message}") }
            if (!dns.hasServiceRequest()) {
                runCatching {
                    wifiP2pManager.awaitP2pAction(
                        channel,
                        busyRetries = timeouts.busyRetries,
                        busyRetryMs = timeouts.busyRetryMs,
                    ) { ch, listener ->
                        dns.addServiceRequest(ch, DNS_SD_CTRL_SERVICE_TYPE, listener)
                    }
                }.onFailure { log("pedido de servicio: ${it.message}") }
            }
        }
        val fin = System.currentTimeMillis() + esperaMs
        var ronda = 0
        var proximoDiscover = 0L
        while (scope.isActive && !closed && System.currentTimeMillis() < fin) {
            // Relanzar discoverServices reinicia la búsqueda en wpa_supplicant y aborta las
            // consultas GAS en curso (las que traen el TXT). Se relanza con la cadencia del
            // prototipo exp01, no en cada vuelta.
            if (System.currentTimeMillis() >= proximoDiscover) {
                ronda++
                withP2p {
                    runCatching {
                        wifiP2pManager.awaitP2pAction(
                            channel,
                            busyRetries = timeouts.busyRetries,
                            busyRetryMs = timeouts.busyRetryMs,
                        ) { ch, listener ->
                            dns.discoverServices(ch, listener)
                        }
                    }.onFailure { log("descubrir servicios: ${it.message}") }
                        .onSuccess { log("descubrir servicios ok (ronda $ronda)") }
                }
                proximoDiscover = System.currentTimeMillis() + timeouts.discoverRetryMs
            }
            if (radioSeen.values.any { it.goSsid.isNotBlank() && it.goPsk.isNotBlank() }) {
                log("ya tengo anuncio; corto la espera")
                break
            }
            val queda = fin - System.currentTimeMillis()
            if (queda <= 0) break
            delay(minOf(300L, queda))
        }
        withP2p {
            runCatching {
                val peers = suspendCancellableCoroutine { cont ->
                    wifiP2pManager.requestPeers(channel) { list ->
                        if (cont.isActive) cont.resume(list)
                    }
                }
                log("visibles ${peers.deviceList.size}: ${peers.deviceList.joinToString { "${it.deviceName}/${it.deviceAddress}" }}")
            }
            runCatching { wifiP2pManager.awaitStopPeerDiscovery(channel) }
            runCatching {
                wifiP2pManager.awaitP2pAction(
                    channel,
                    busyRetries = timeouts.busyRetries,
                    busyRetryMs = timeouts.busyRetryMs,
                ) { ch, listener ->
                    dns.stopDiscovery(ch, listener)
                }
            }
        }
        log("fin de búsqueda: ${radioSeen.size} anuncios — dejo de buscar")
        return radioSeen.values.toList()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private suspend fun connectToParent(parentPeer: ServiceStructure): Network {
        joining = true
        try {
            val net = withTimeout(timeouts.staConnectMs) {
                staLink.connect(parentPeer.goSsid, parentPeer.goPsk)
            }
            parent = parentPeer
            activeStaNetwork = net
            _uplink.value = buildUplink(connectivityManager, net, staBssid, parentPeer.nid.pctHex())
            ingest(listOf(parentPeer), staToPeer = true)
            lastAdvertised = null
            runCatching { ensureGo() }.onFailure { log("grupo propio tras asociarme: ${it.message}") }
            advertiseCurrent()
            setRunning()
            log("asociado al padre ${parentPeer.goSsid}")
            return net
        } finally {
            joining = false
        }
    }

    /** L2 detectó bucle (los dos hicimos STA al otro) y a este nodo le toca quedarse de padre. */
    fun dropSta() {
        staLink.disconnect()
        parent = null
        activeStaNetwork = null
        _uplink.value = null
        lastAdvertised = null
        scope.launch {
            advertiseCurrent()
            setRunning()
        }
        log("solté al padre")
    }

    private suspend fun advertiseCurrent() {
        if (goSsid.isBlank()) return
        val struct = currentService()
        if (struct == lastAdvertised) return
        withP2p { advertiseLocked(struct) }
        lastAdvertised = struct
    }

    private suspend fun advertiseLocked(struct: ServiceStructure) {
        runCatching {
            wifiP2pManager.awaitP2pAction(channel) { ch, listener ->
                dns.clearLocalServices(ch, listener)
            }
        }
        val added = runCatching {
            wifiP2pManager.awaitP2pAction(
                channel,
                busyRetries = timeouts.busyRetries,
                busyRetryMs = timeouts.busyRetryMs,
            ) { ch, listener ->
                dns.advertise(
                    channel = ch,
                    serviceName = encodeDnsSdInstance(struct),
                    serviceType = DNS_SD_CTRL_SERVICE_TYPE,
                    txtRecord = encodeDnsSdTxt(struct),
                    listener = listener,
                )
            }
        }
        if (added.isFailure) {
            log("anuncio: ${added.exceptionOrNull()?.message}")
        } else {
            log("anuncio claves=${encodeDnsSdTxt(struct).keys}")
        }
        log("anuncio ${if (parent == null) "raíz" else "puente"} profundidad=${struct.depth} ssid=${struct.goSsid}")
    }

    private suspend fun refreshChildCount() {
        if (goSsid.isBlank()) return
        runCatching {
            withP2p {
                val group = wifiP2pManager.awaitGroupInfo(
                    channel = channel,
                    maxAttempts = 2,
                    delayMs = timeouts.groupInfoRetryMs,
                )
                childCount = group.clientList?.size ?: 0
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    suspend fun close() {
        closed = true
        meshJob?.cancelAndJoin()
        runCatching {
            withTimeout(8_000) { withP2p { limpiarRadioLocked("cierre") } }
        }.onFailure { log("cierre: ${it.message}") }
        p2pRuntime.close()
        setState(NodeState.Closed)
        publish(action = "cerrado", searching = false)
        log("cerrado")
    }

    private fun onStaLost() {
        if (closed || parent == null) return
        log("perdí al padre")
        parent = null
        activeStaNetwork = null
        _uplink.value = null
        lastAdvertised = null
        scope.launch {
            advertiseCurrent()
            setRunning()
        }
    }

    private fun currentRole(): Role = if (parent == null) Role.ROOT else Role.BRIDGE

    private fun currentDepth(): Int = parent?.let { it.depth + 1 } ?: 0

    private fun currentService(): ServiceStructure = ServiceStructure(
        nid = nodeConfig.nodeId,
        role = currentRole(),
        depth = currentDepth(),
        ctrlPort = DEFAULT_CTRL_PORT,
        goSsid = goSsid,
        goPsk = goPsk,
        childCount = childCount,
    )

    private fun setState(state: NodeState) {
        _nodeState.value = state
    }

    private fun setRunning() {
        setState(
            NodeState.Running(
                role = currentRole(),
                parentId = parent?.nid,
                depth = currentDepth(),
            ),
        )
        publish()
    }

    private fun ingest(candidates: List<ServiceStructure>, staToPeer: Boolean = false) {
        val now = System.currentTimeMillis()
        val next = graph.peers.toMutableMap()
        for (service in candidates) {
            val key = service.nid.pctHex().take(8)
            val previous = next[key]
            val merged = mergeSeen(previous?.service, service)
            next[key] = SeenPeer(
                service = merged,
                lastSeenMs = now,
                staToPeer = staToPeer || previous?.staToPeer == true,
                edgeVsSelf = orientEdge(nodeConfig.nodeId, currentDepth(), merged),
            )
        }
        graph = RelativeGraph(next)
        publish()
    }

    private fun mergeSeen(previous: ServiceStructure?, incoming: ServiceStructure): ServiceStructure {
        if (previous == null) return incoming
        val fullNid = if (incoming.nid.pctHex().endsWith("0".repeat(24))) previous.nid else incoming.nid
        return incoming.copy(
            nid = fullNid,
            goSsid = incoming.goSsid.ifBlank { previous.goSsid },
            goPsk = incoming.goPsk.ifBlank { previous.goPsk },
            depth = incoming.depth,
            role = incoming.role,
            childCount = maxOf(incoming.childCount, previous.childCount),
            p2pDeviceAddress = incoming.p2pDeviceAddress.ifBlank { previous.p2pDeviceAddress },
        )
    }

    private fun publish(
        action: String = _snapshot.value.action,
        searching: Boolean = _snapshot.value.searching,
    ) {
        _snapshot.value = PhysicalSnapshot(
            nodeId = nodeConfig.nodeId.pctHex(),
            role = currentRole(),
            depth = currentDepth(),
            parentId = parent?.nid?.pctHex(),
            goSsid = goSsid,
            goReady = goSsid.isNotBlank(),
            staSsid = parent?.goSsid,
            staConnected = parent != null && activeStaNetwork != null,
            searching = searching,
            childCount = childCount,
            action = action,
            graph = graph,
        )
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        _logs.tryEmit(message)
    }

    private companion object {
        const val TAG = "PctMesh"
        const val DEFAULT_CTRL_PORT = 8765
    }
}
