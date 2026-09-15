package co.uan.pct.proto.exp01.gostat.data.sta

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import co.uan.pct.proto.exp01.gostat.data.p2p.model.PctCtrlRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LegacyStaRepository(
    private val app: Application,
) {
    private val connectivityManager: ConnectivityManager =
        app.getSystemService(ConnectivityManager::class.java)

    private val _staState = MutableStateFlow<StaState>(StaState.Idle)
    val staState: StateFlow<StaState> = _staState.asStateFlow()

    private var activeNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    fun connect(record: PctCtrlRecord) {
        disconnect()
        _staState.value = StaState.Connecting

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(record.goSsid)
            .setWpa2Passphrase(record.goPsk)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetwork = network
                connectivityManager.bindProcessToNetwork(network)
                _staState.value = StaState.Connected(record.goSsid)
            }

            override fun onUnavailable() {
                networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
                networkCallback = null
                connectivityManager.bindProcessToNetwork(null)
                activeNetwork = null
                _staState.value = StaState.Error("No se pudo asociar a ${record.goSsid}")
            }

            override fun onLost(network: Network) {
                if (activeNetwork == network) {
                    _staState.value = StaState.Idle
                    activeNetwork = null
                }
            }
        }

        networkCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback)
        } catch (e: SecurityException) {
            networkCallback = null
            _staState.value = StaState.Error(
                "Permiso CHANGE_NETWORK_STATE denegado; reinstala la app o concede permisos de red",
            )
        }
    }

    fun connectIfSupported(record: PctCtrlRecord) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connect(record)
        } else {
            _staState.value = StaState.Error("STA legacy requiere API 29+")
        }
    }

    fun disconnect() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        networkCallback = null
        connectivityManager.bindProcessToNetwork(null)
        activeNetwork = null
        _staState.value = StaState.Idle
    }
}
