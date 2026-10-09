package co.uan.pct.lib.core.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import co.uan.pct.lib.core.logging.PctLog
import co.uan.pct.lib.core.types.NodeId
import co.uan.pct.lib.core.types.Role
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * - **Entrante:** [ServerSocket] en el SoftAP/GO (sin [Network.bindSocket] a la STA).
 * - **Saliente:** STA → PING/PONG → ANNOUNCE/ANNOUNCE_OK por [ControlSession].
 */
@OptIn(ExperimentalUuidApi::class)
class ControlLink(
    context: Context,
    private val localNodeId: NodeId,
    private val roleFlow: StateFlow<Role>,
    private val staNetworkFlow: StateFlow<Network?>,
    private val neighborTable: NeighborTable,
) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active = false

    private var goListenSocket: ServerSocket? = null
    private var goAcceptJob: Job? = null
    private var goListenWatcherJob: Job? = null

    private var staUpstreamJob: Job? = null
    private var staNetworkWatcherJob: Job? = null
    private var upstreamSession: ControlSession? = null

    private val goSessions =
        Collections.synchronizedSet(mutableSetOf<ControlSession>())

    fun start() {
        if (active) return
        active = true

        goListenWatcherJob = scope.launch {
            roleFlow.collect { role ->
                if (role.hasGo()) {
                    ensureListeningOnGoSoftAp()
                } else {
                    stopListeningOnGoSoftAp()
                    closeAllGoSessions()
                }
            }
        }

        staNetworkWatcherJob = scope.launch {
            staNetworkFlow.collect { staNetwork ->
                staUpstreamJob?.cancel()
                staUpstreamJob = null
                upstreamSession?.closeQuietly()
                upstreamSession = null
                if (staNetwork != null) {
                    staUpstreamJob = launch { maintainUpstreamOverSta(staNetwork) }
                }
            }
        }
    }

    fun stop() {
        active = false
        goListenWatcherJob?.cancel()
        staNetworkWatcherJob?.cancel()
        staUpstreamJob?.cancel()
        upstreamSession?.closeQuietly()
        upstreamSession = null
        closeAllGoSessions()
        stopListeningOnGoSoftAp()
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        neighborTable.clear()
    }

    private fun ensureListeningOnGoSoftAp() {
        if (goListenSocket != null) return
        runCatching {
            ServerSocket(CONTROL_PORT).apply { reuseAddress = true }
        }.onSuccess { socket ->
            goListenSocket = socket
            PctLog.event("l2: listen go :$CONTROL_PORT")
            goAcceptJob = scope.launch { acceptLoopOnGo(socket) }
        }.onFailure { t ->
            PctLog.event("l2: listen go fallo ${t.message}")
        }
    }

    private fun stopListeningOnGoSoftAp() {
        goAcceptJob?.cancel()
        goAcceptJob = null
        runCatching { goListenSocket?.close() }
        goListenSocket = null
    }

    private suspend fun acceptLoopOnGo(server: ServerSocket) {
        while (active && scope.isActive) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch { openGoInboundSession(client) }
        }
    }

    private fun openGoInboundSession(socket: Socket) {
        var sessionRef: ControlSession? = null
        val session = ControlSession(
            socket = socket,
            scope = scope,
            localNodeId = localNodeId,
            neighborTable = neighborTable,
            connectivity = connectivity,
            mode = ControlSession.Mode.GO_INBOUND,
            onClosed = { sessionRef?.let { goSessions.remove(it) } },
        )
        sessionRef = session
        goSessions.add(session)
        session.start()
        PctLog.event("l2: go session abierta")
    }

    private fun closeAllGoSessions() {
        goSessions.toList().forEach { it.closeQuietly() }
        goSessions.clear()
    }

    private suspend fun maintainUpstreamOverSta(staNetwork: Network) {
        while (active && scope.isActive && staNetworkFlow.value == staNetwork) {
            val gateway = StaParentNetwork.gatewayForControlTcp(connectivity, staNetwork)
            val localIp = StaParentNetwork.hostIpv4ForAnnounce(connectivity, staNetwork)
            if (gateway == null || localIp == null) {
                PctLog.event("l2: upstream espera gw/ip gw=$gateway ip=$localIp")
                delay(RETRY_DELAY)
                continue
            }
            val connected = runCatching {
                Socket().apply {
                    staNetwork.bindSocket(this)
                    connect(InetSocketAddress(gateway, CONTROL_PORT), CONNECT_TIMEOUT_MS)
                }
            }.getOrNull()
            if (connected == null) {
                delay(RETRY_DELAY)
                continue
            }
            val session = ControlSession(
                socket = connected,
                scope = scope,
                localNodeId = localNodeId,
                neighborTable = neighborTable,
                connectivity = connectivity,
                mode = ControlSession.Mode.UPSTREAM,
            )
            upstreamSession = session
            session.start()
            runCatching {
                session.runUpstreamJoin(localIp)
            }.onFailure { t ->
                PctLog.event("l2: upstream session fallo ${t.message}")
                session.closeQuietly()
                upstreamSession = null
                delay(RETRY_DELAY)
                continue
            }
            while (active && scope.isActive && staNetworkFlow.value == staNetwork && upstreamSession === session) {
                delay(1.seconds)
            }
            session.closeQuietly()
            if (upstreamSession === session) upstreamSession = null
        }
    }

    private fun Role.hasGo(): Boolean = this != Role.ISLAND

    private companion object {
        const val CONTROL_PORT = 8765
        const val CONNECT_TIMEOUT_MS = 8_000
        val RETRY_DELAY = 1.seconds
    }
}
