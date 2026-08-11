package co.uan.pct.lib.core.api

/**
 * Estado vivo de subistemas P2P/STA/DNS para pantallas de laboratorio.
 * No forma parte del contrato de topología; solo diagnóstico.
 */
data class PctDebugSnapshot(
    /** Qué está haciendo el orquestador ahora (texto humano). */
    val action: String = "Sin iniciar",
    val goStatus: String = "idle",
    val goSsid: String? = null,
    val goIsOwner: Boolean? = null,
    val staStatus: String = "idle",
    val staSsid: String? = null,
    val dnsDiscovering: Boolean = false,
    val dnsAdvertising: Boolean = false,
    val dnsPhase: String = "Idle",
    val peerCount: Int = 0,
    val servicesSeen: Int = 0,
    val pctCtrlSeen: Int = 0,
    val txtCallbacks: Int = 0,
    val discoveryTicks: Int = 0,
    val hint: String = "",
    val candidates: List<PctDebugCandidate> = emptyList(),
)

data class PctDebugCandidate(
    val deviceName: String,
    val nodeId: String,
    val role: String,
    val hop: Int,
    val goSsid: String,
    val deviceAddress: String,
    val instanceName: String,
)
