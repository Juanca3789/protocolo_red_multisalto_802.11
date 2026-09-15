package co.uan.pct.proto.exp01.gostat.data.p2p

import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import co.uan.pct.proto.exp01.gostat.data.p2p.model.GoState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class GoRepository(
    private val p2p: P2pChannelHolder,
) : P2pEventListener {

    private val force = P2pForceClear(p2p)
    private val handler = Handler(Looper.getMainLooper())

    private val _goState = MutableStateFlow<GoState>(GoState.Idle)
    val goState: StateFlow<GoState> = _goState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var wantGroup = false
    private var createAttempts = 0

    init {
        p2p.addListener(this)
    }

    fun createGroup() {
        wantGroup = true
        createAttempts = 0
        val current = _goState.value
        if (current is GoState.Ready && current.isGroupOwner) {
            emit("GO ya listo; no recreo")
            return
        }
        _goState.value = GoState.Creating
        attemptCreate()
    }

    fun removeGroup() {
        wantGroup = false
        createAttempts = 0
        _goState.value = GoState.Idle
        actuallyRemove(attempt = 0)
    }

    fun requestGroupInfo() {
        p2p.manager.requestGroupInfo(p2p.channel) { group ->
            if (!wantGroup) {
                if (group != null) actuallyRemove(attempt = 0)
                else _goState.value = GoState.Idle
                return@requestGroupInfo
            }
            if (group == null || !group.isGroupOwner) {
                return@requestGroupInfo
            }
            _goState.value = GoState.Ready(
                ssid = group.networkName.orEmpty(),
                psk = group.passphrase.orEmpty(),
                isGroupOwner = true,
            )
        }
    }

    override fun onP2pIntent(intent: Intent) {
        if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
        val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
            WifiP2pManager.EXTRA_NETWORK_INFO,
        )
        val connected = networkInfo?.isConnected == true
        if (connected) {
            if (wantGroup) requestGroupInfo()
            else actuallyRemove(attempt = 0)
            return
        }
        if (!wantGroup) {
            _goState.value = GoState.Idle
            return
        }
        if (_goState.value !is GoState.Creating) {
            emit("GO se cayó; recreo")
            _goState.value = GoState.Creating
            attemptCreate()
        }
    }

    fun close() {
        wantGroup = false
        p2p.removeListener(this)
    }

    private fun attemptCreate() {
        if (!wantGroup) return
        p2p.manager.createGroup(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (!wantGroup) {
                    emit("createGroup tardío; mato el grupo")
                    actuallyRemove(attempt = 0)
                    return
                }
                requestGroupInfo()
            }

            override fun onFailure(reason: Int) {
                if (!wantGroup) {
                    _goState.value = GoState.Idle
                    return
                }
                val label = P2pFailureReasons.describe(reason)
                if (reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR) {
                    recoverThenRetry("createGroup $label")
                    return
                }
                _goState.value = GoState.Error("createGroup falló: $label")
            }
        })
    }

    private fun recoverThenRetry(why: String) {
        createAttempts++
        if (createAttempts > 6) {
            _goState.value = GoState.Error("$why; no pude liberar la cola")
            return
        }
        emit("$why; mato lo que ocupe el P2P y reintento ($createAttempts)")
        force.free(killGroup = true, killLocalServices = true) {
            if (wantGroup) attemptCreate()
        }
    }

    private fun actuallyRemove(attempt: Int) {
        if (wantGroup) return
        p2p.manager.removeGroup(p2p.channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (!wantGroup) _goState.value = GoState.Idle
            }

            override fun onFailure(reason: Int) {
                if (wantGroup) {
                    return
                }
                val label = P2pFailureReasons.describe(reason)
                if (attempt >= 6) {
                    emit("removeGroup $label; el grupo puede seguir vivo")
                    _goState.value = GoState.Error("removeGroup falló: $label")
                    return
                }
                emit("removeGroup $label; mato cola y reintento")
                force.free(killGroup = true, killLocalServices = true) {
                    if (!wantGroup) {
                        handler.postDelayed({ actuallyRemove(attempt + 1) }, 200)
                    }
                }
            }
        })
    }

    private fun emit(message: String) {
        _events.tryEmit(message)
    }
}
