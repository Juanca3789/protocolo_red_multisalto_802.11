package co.uan.pct.lib.core.internal.p2p

import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import co.uan.pct.lib.core.internal.p2p.model.GoClient
import co.uan.pct.lib.core.internal.p2p.model.GoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GoRepository(
    private val p2p: P2pChannelHolder,
) : P2pEventListener {

    private val _goState = MutableStateFlow<GoState>(GoState.Idle)
    val goState: StateFlow<GoState> = _goState.asStateFlow()

    private val _clients = MutableStateFlow<List<GoClient>>(emptyList())
    val clients: StateFlow<List<GoClient>> = _clients.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var groupInfoAttempts = 0

    init {
        p2p.addListener(this)
    }

    fun createGroup() {
        groupInfoAttempts = 0
        _goState.value = GoState.Creating
        p2p.manager.createGroup(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // El grupo a menudo aún no está en el binder: reintentar, no Error inmediato
                requestGroupInfo()
            }

            override fun onFailure(reason: Int) {
                _goState.value = GoState.Error(
                    "createGroup falló: ${P2pFailureReasons.describe(reason)}",
                )
                _clients.value = emptyList()
            }
        })
    }

    fun requestGroupInfo() {
        p2p.manager.requestGroupInfo(p2p.channel) { group ->
            if (group == null) {
                when (val state = _goState.value) {
                    is GoState.Creating -> {
                        if (groupInfoAttempts < MAX_GROUP_INFO_ATTEMPTS) {
                            groupInfoAttempts++
                            mainHandler.postDelayed(
                                { requestGroupInfo() },
                                GROUP_INFO_RETRY_MS,
                            )
                        } else {
                            _goState.value = GoState.Error("Grupo P2P no disponible")
                            _clients.value = emptyList()
                        }
                    }
                    is GoState.Ready -> {
                        // Confirmar pérdida real del grupo (evitar flaps STA/GO)
                        _clients.value = emptyList()
                        _goState.value = GoState.Idle
                    }
                    else -> {
                        _clients.value = emptyList()
                    }
                }
                return@requestGroupInfo
            }

            groupInfoAttempts = 0
            val mapped = group.clientList.orEmpty().map { device ->
                GoClient(
                    deviceAddress = device.deviceAddress.orEmpty(),
                    deviceName = device.deviceName.orEmpty().ifBlank { "client" },
                    status = device.status,
                )
            }
            _clients.value = mapped

            val ready = GoState.Ready(
                ssid = group.networkName.orEmpty(),
                psk = group.passphrase.orEmpty(),
                isGroupOwner = group.isGroupOwner,
            )
            when (_goState.value) {
                is GoState.Creating, is GoState.Ready, GoState.Idle, is GoState.Error -> {
                    _goState.value = ready
                }
            }
        }
    }

    fun removeGroup() {
        groupInfoAttempts = 0
        mainHandler.removeCallbacksAndMessages(null)
        p2p.manager.removeGroup(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _clients.value = emptyList()
                _goState.value = GoState.Idle
            }

            override fun onFailure(reason: Int) {
                _clients.value = emptyList()
                _goState.value = GoState.Idle
            }
        })
    }

    fun forceRemoveGroup() {
        groupInfoAttempts = 0
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            p2p.manager.removeGroup(p2p.channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _clients.value = emptyList()
                    _goState.value = GoState.Idle
                }

                override fun onFailure(reason: Int) {
                    _clients.value = emptyList()
                    _goState.value = GoState.Idle
                }
            })
        }
        _clients.value = emptyList()
        _goState.value = GoState.Idle
    }

    override fun onP2pIntent(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                    WifiP2pManager.EXTRA_NETWORK_INFO,
                )
                val connected = networkInfo?.isConnected == true
                when (_goState.value) {
                    is GoState.Creating -> requestGroupInfo()
                    is GoState.Ready -> {
                        // Siempre refrescar: clientes nuevos o verificar si el grupo sigue
                        requestGroupInfo()
                    }
                    else -> {
                        if (connected) requestGroupInfo()
                    }
                }
                // No pasar Ready→Idle solo por !connected: en STA+GO el intent flapea
                // y dejaría el GO “muerto” en UI aunque el SoftAP siga arriba.
            }
        }
    }

    fun close() {
        mainHandler.removeCallbacksAndMessages(null)
        p2p.removeListener(this)
    }

    private companion object {
        const val MAX_GROUP_INFO_ATTEMPTS = 8
        const val GROUP_INFO_RETRY_MS = 500L
    }
}
