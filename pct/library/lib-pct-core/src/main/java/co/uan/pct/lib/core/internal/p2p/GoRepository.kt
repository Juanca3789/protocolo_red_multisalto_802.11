package co.uan.pct.lib.core.internal.p2p

import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import co.uan.pct.lib.core.internal.p2p.model.GoState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GoRepository(
    private val p2p: P2pChannelHolder,
) : P2pEventListener {

    private val _goState = MutableStateFlow<GoState>(GoState.Idle)
    val goState: StateFlow<GoState> = _goState.asStateFlow()

    init {
        p2p.addListener(this)
    }

    fun createGroup() {
        _goState.value = GoState.Creating
        p2p.manager.createGroup(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestGroupInfo()
            }

            override fun onFailure(reason: Int) {
                _goState.value = GoState.Error(
                    "createGroup falló: ${P2pFailureReasons.describe(reason)}",
                )
            }
        })
    }

    fun requestGroupInfo() {
        p2p.manager.requestGroupInfo(p2p.channel) { group ->
            if (group == null) {
                _goState.value = GoState.Error("Grupo P2P no disponible")
                return@requestGroupInfo
            }
            _goState.value = GoState.Ready(
                ssid = group.networkName.orEmpty(),
                psk = group.passphrase.orEmpty(),
                isGroupOwner = group.isGroupOwner,
            )
        }
    }

    fun removeGroup() {
        p2p.manager.removeGroup(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _goState.value = GoState.Idle
            }

            override fun onFailure(reason: Int) {
                // Grupo inexistente / ya removido: tratar como idle (anti-zombi)
                _goState.value = GoState.Idle
            }
        })
    }

    /** Limpieza agresiva sin callbacks de estado (arranque / close). */
    fun forceRemoveGroup() {
        runCatching {
            p2p.manager.removeGroup(p2p.channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _goState.value = GoState.Idle
                }

                override fun onFailure(reason: Int) {
                    _goState.value = GoState.Idle
                }
            })
        }
        _goState.value = GoState.Idle
    }

    override fun onP2pIntent(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                    WifiP2pManager.EXTRA_NETWORK_INFO,
                )
                if (networkInfo?.isConnected == false && _goState.value !is GoState.Creating) {
                    _goState.value = GoState.Idle
                    return
                }
                if (_goState.value is GoState.Creating) {
                    requestGroupInfo()
                }
            }
        }
    }

    fun close() {
        p2p.removeListener(this)
    }
}
