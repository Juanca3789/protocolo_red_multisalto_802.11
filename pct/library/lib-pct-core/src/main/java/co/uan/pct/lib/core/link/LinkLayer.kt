package co.uan.pct.lib.core.link

import android.util.Log
import co.uan.pct.lib.core.net.HopTable
import co.uan.pct.lib.core.net.MeshSocket
import co.uan.pct.lib.core.net.UserCodec
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Capa 2. No sabe de Wi‑Fi: recibe de L1 un [Uplink] cuando hay STA al padre y, con eso,
 * abre el TCP de control `:8765` hacia él. En su propio GO escucha `:8765` (control) y
 * `:8766` (datos de usuario).
 *
 * - Sentido de la arista = sentido del socket. Quien conecta es hijo.
 * - Por control van `HI` (quién soy + rutas), `TAB` (rutas), `PING`/`PONG`.
 * - El socket de datos lo abre el hijo hacia el padre y empieza con sus 16 bytes de nid;
 *   un solo TCP de datos por arista, en ambos sentidos.
 * - La tabla de rutas vive aquí (nid → siguiente nid). Las IP no salen de esta clase.
 */
class LinkLayer(
    val nodeId: String,
    private val uplinks: StateFlow<Uplink?>,
    private val onLoop: (peerNid: String) -> Unit = {},
    private val ctrlPort: Int = CTRL_PORT,
    private val dataPort: Int = DATA_PORT,
    private val bindAddress: InetAddress? = null,
    private val keepAliveMs: Long = 5_000,
    private val deadAfterMs: Long = 15_000,
) {
    private val table = RouteTable(nodeId)
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, LinkSession>()
    private val dataOut = linkedMapOf<String, Socket>()
    private val job = SupervisorJob()
    private val errors = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "enlace: ${t.message}", t)
        _logs.tryEmit("enlace: ${t.message}")
    }
    private val scope = CoroutineScope(job + Dispatchers.IO + errors)
    val mesh = MeshSocket(nodeId, LinkHops())

    private val _snapshot = MutableStateFlow(LinkSnapshot(tree = nodeId))
    val snapshot: StateFlow<LinkSnapshot> = _snapshot.asStateFlow()
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private var treeRoot: String = nodeId
    private var selfDepth: Int = 0
    private var ctrlServer: ServerSocket = ServerSocket()
    private var dataServer: ServerSocket = ServerSocket()
    private var started = false
    private var parentJob: Job? = null
    private var currentUplink: Uplink? = null

    /** Puertos reales (útiles cuando se pide puerto 0 en pruebas). */
    val boundCtrlPort: Int get() = ctrlServer.localPort
    val boundDataPort: Int get() = dataServer.localPort

    fun start() {
        if (started) return
        started = true
        ctrlServer = ServerSocket(ctrlPort, 16, bindAddress)
        dataServer = ServerSocket(dataPort, 16, bindAddress)
        scope.launch { acceptLoop(ctrlServer) { runSession(LinkSession(it, isParent = false), null) } }
        scope.launch { acceptLoop(dataServer) { onDataSocket(it) } }
        scope.launch { keepAliveLoop() }
        scope.launch { watchUplink() }
        log("enlace escucha :$boundCtrlPort / :$boundDataPort")
        _snapshot.value = LinkSnapshot(tree = treeRoot, action = "enlace listo")
    }

    fun close() {
        started = false
        job.cancel()
        runCatching { ctrlServer.close() }
        runCatching { dataServer.close() }
        // Las corrutinas ya están canceladas pero pueden estar terminando: copiar antes de iterar.
        runCatching { ArrayList(sessions.values) }.getOrDefault(emptyList()).forEach { it.close() }
        runCatching { ArrayList(dataOut.values) }.getOrDefault(emptyList()).forEach { runCatching { it.close() } }
        runCatching { sessions.clear() }
        runCatching { dataOut.clear() }
    }

    fun sendUser(destinationNid: String, payload: ByteArray) {
        scope.launch { mesh.send(destinationNid, payload) }
    }

    private suspend fun watchUplink() {
        uplinks.collect { uplink ->
            parentJob?.cancel()
            parentJob = null
            val previous = mutex.withLock {
                currentUplink = uplink
                sessions.values.firstOrNull { it.isParent }
            }
            previous?.close()
            if (uplink != null) {
                log("asociado al padre → TCP $uplink")
                parentJob = scope.launch { connectParent(uplink) }
            }
        }
    }

    private suspend fun connectParent(uplink: Uplink) {
        val socket = Socket()
        val connected = runCatching {
            uplink.bind(socket)
            withContext(Dispatchers.IO) {
                socket.connect(InetSocketAddress(uplink.address, uplink.ctrlPort), CONNECT_MS)
            }
        }
        if (connected.isFailure) {
            log("connect :${uplink.ctrlPort} ${connected.exceptionOrNull()?.message}")
            runCatching { socket.close() }
            return
        }
        runSession(LinkSession(socket, isParent = true), uplink)
    }

    private suspend fun acceptLoop(server: ServerSocket, onSock: suspend (Socket) -> Unit) {
        while (scope.isActive) {
            val sock = runCatching { withContext(Dispatchers.IO) { server.accept() } }.getOrNull() ?: break
            scope.launch { onSock(sock) }
        }
    }

    private suspend fun runSession(session: LinkSession, uplink: Uplink?) {
        log("TCP ${if (session.isParent) "out" else "in"} ${session.remoteIp}")
        runCatching { session.send(hiMsg()) }
        try {
            while (scope.isActive && !session.socket.isClosed) {
                val msg = withContext(Dispatchers.IO) { session.read() } ?: break
                handleMsg(session, msg, uplink)
            }
        } catch (t: Throwable) {
            log("TCP ${session.remoteIp}: ${t.message}")
        } finally {
            session.close()
            val nid = session.peerNid
            if (nid != null) dropPeer(nid, session)
        }
    }

    private suspend fun dropPeer(nid: String, session: LinkSession) {
        val wasMine = mutex.withLock {
            if (sessions[nid] !== session) return@withLock false
            sessions.remove(nid)
            dataOut.remove(nid)?.let { runCatching { it.close() } }
            table.dropNeighbor(nid)
            if (session.isParent) {
                treeRoot = nodeId
                selfDepth = 0
            }
            true
        }
        if (!wasMine) return
        log("vecino caído ${nid.take(8)}")
        gossipTab(exceptNid = nid)
        publish("vecino caído")
    }

    private suspend fun handleMsg(session: LinkSession, msg: CtrlMsg, uplink: Uplink?) {
        when (msg) {
            is CtrlMsg.Hi -> onHi(session, msg, uplink)
            is CtrlMsg.Tab -> {
                val changed = mutex.withLock {
                    session.peerNid?.let { table.mergeFrom(it, msg.routes) } ?: false
                }
                if (changed) {
                    publish("tabla")
                    gossipTab(exceptNid = session.peerNid)
                }
            }
            is CtrlMsg.Ping -> runCatching { session.send(CtrlMsg.Pong(msg.seq)) }
            is CtrlMsg.Pong -> Unit
        }
    }

    private class HiDecision(
        val keep: Boolean,
        val closeFirst: LinkSession?,
        val soltarSta: Boolean,
        val motivo: String?,
    )

    private suspend fun onHi(session: LinkSession, hi: CtrlMsg.Hi, uplink: Uplink?) {
        if (UserCodec.sameNid(hi.nid, nodeId)) {
            log("HI de mí mismo; cierro")
            session.close()
            return
        }
        // Bucle: los dos hicimos STA al otro, así que hay dos TCP con el mismo vecino y sentidos
        // opuestos. Regla fija e idéntica en ambos: el nid mayor es el hijo. El menor queda de
        // padre y suelta su STA (onLoop) para no reconectar. Sin negociación por mensajes.
        //
        // Decidir e instalar van en UNA sola sección del mutex: dos HI del mismo vecino que
        // lleguen a la vez no pueden decidir ambos "instalar" y pisarse.
        val iAmLower = nodeId < hi.nid
        val decision = mutex.withLock {
            var soltarSta = false
            var motivo: String? = null
            // Detección independiente del orden: si acepto una entrada del mismo nodo al que yo
            // hice STA, hay bucle aunque mi TCP saliente haya muerto antes de procesar su HI.
            val staHaciaEste = currentUplink?.parentNid?.let { UserCodec.sameNid(it, hi.nid) } == true
            if (!session.isParent && staHaciaEste) {
                if (iAmLower) {
                    soltarSta = true
                } else {
                    return@withLock HiDecision(
                        keep = false,
                        closeFirst = null,
                        soltarSta = false,
                        motivo = "soy el mayor, sigo de hijo y descarto su entrada",
                    )
                }
            }
            val existing = sessions[hi.nid]
            var closeFirst: LinkSession? = null
            val keep = when {
                existing == null || existing === session -> true
                existing.isParent == session.isParent -> {
                    closeFirst = existing
                    true
                }
                else -> {
                    if (iAmLower) soltarSta = true
                    val keepThis = if (nodeId > hi.nid) session.isParent else !session.isParent
                    if (keepThis) closeFirst = existing else motivo = "descarto esta dirección"
                    keepThis
                }
            }
            if (keep) {
                session.peerNid = hi.nid
                session.peerDepth = hi.depth
                session.peerTree = hi.tree
                sessions[hi.nid] = session
                table.installNeighbor(hi.nid, session.remoteIp, asParent = session.isParent)
                table.mergeFrom(hi.nid, hi.routes)
                if (session.isParent) {
                    treeRoot = hi.tree
                    selfDepth = hi.depth + 1
                } else if (table.parentId == null) {
                    // Quedé de padre (posible reorientación por bucle): vuelvo a raíz local.
                    treeRoot = nodeId
                    selfDepth = 0
                }
            }
            HiDecision(keep, closeFirst, soltarSta, motivo)
        }
        decision.closeFirst?.close()
        if (!decision.keep) {
            log("bucle con ${hi.nid.take(8)}: ${decision.motivo}")
            session.close()
            if (decision.soltarSta) onLoop(hi.nid)
            return
        }
        if (decision.soltarSta) onLoop(hi.nid)
        log(
            "arista ${if (session.isParent) "padre" else "hijo"} ↔ ${hi.nid.take(8)} " +
                "árbol=${hi.tree.take(8)} rutas=${hi.routes.size}",
        )
        runCatching { session.send(CtrlMsg.Tab(mutex.withLock { table.advertise() })) }
        gossipTab(exceptNid = hi.nid)
        if (session.isParent && uplink != null) {
            scope.launch { openData(hi.nid, session, uplink) }
        }
        publish(if (session.isParent) "hijo de ${hi.nid.take(8)}" else "padre de ${hi.nid.take(8)}")
    }

    /**
     * Solo el hijo abre `:8766`; primero manda sus 16 bytes de nid para que el padre lo ubique.
     * Mientras la sesión de control siga viva, si el socket de datos se cae se vuelve a abrir:
     * el control es el que dice si el vecino existe; los datos son solo el canal.
     */
    private suspend fun openData(parentNid: String, session: LinkSession, uplink: Uplink) {
        while (scope.isActive && sessionAlive(parentNid, session)) {
            val sock = Socket()
            val ok = runCatching {
                uplink.bind(sock)
                withContext(Dispatchers.IO) {
                    sock.connect(InetSocketAddress(uplink.address, uplink.dataPort), CONNECT_MS)
                    sock.getOutputStream().write(UserCodec.nid16(nodeId))
                    sock.getOutputStream().flush()
                }
            }
            if (ok.isFailure) {
                log("datos :${uplink.dataPort} ${ok.exceptionOrNull()?.message}")
                runCatching { sock.close() }
                delay(DATA_RETRY_MS)
                continue
            }
            mutex.withLock {
                dataOut[parentNid]?.let { runCatching { it.close() } }
                dataOut[parentNid] = sock
                session.dataOpen = true
            }
            publish("datos abiertos con ${parentNid.take(8)}")
            readUser(parentNid, sock)
            if (!sessionAlive(parentNid, session)) break
            log("datos con ${parentNid.take(8)} cerrados; reabro")
            delay(DATA_RETRY_MS)
        }
    }

    private suspend fun sessionAlive(nid: String, session: LinkSession): Boolean =
        !session.socket.isClosed && mutex.withLock { sessions[nid] === session }

    private suspend fun onDataSocket(socket: Socket) {
        val nidBytes = ByteArray(16)
        val header = runCatching {
            withContext(Dispatchers.IO) { socket.getInputStream().readNBytes(nidBytes, 0, 16) }
        }.getOrDefault(0)
        if (header != 16) {
            runCatching { socket.close() }
            return
        }
        val nid = nidBytes.joinToString("") { "%02x".format(it) }
        mutex.withLock {
            dataOut[nid]?.let { runCatching { it.close() } }
            dataOut[nid] = socket
            sessions[nid]?.dataOpen = true
        }
        publish("datos abiertos con ${nid.take(8)}")
        readUser(nid, socket)
    }

    private suspend fun readUser(nid: String, socket: Socket) {
        try {
            val input = socket.getInputStream()
            while (scope.isActive && !socket.isClosed) {
                val frame = withContext(Dispatchers.IO) { UserCodec.read(input) } ?: break
                mesh.onHop(nid, frame)
            }
        } finally {
            mutex.withLock {
                if (dataOut[nid] === socket) {
                    dataOut.remove(nid)
                    sessions[nid]?.dataOpen = false
                }
            }
            runCatching { socket.close() }
        }
    }

    private suspend fun writeUser(nextNid: String, bytes: ByteArray): Boolean {
        val sock = mutex.withLock { dataOut[nextNid] } ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                synchronized(sock) {
                    sock.getOutputStream().write(bytes)
                    sock.getOutputStream().flush()
                }
                true
            }.getOrDefault(false)
        }
    }

    private inner class LinkHops : HopTable {
        override suspend fun nextNid(dest: String): String? = mutex.withLock {
            table.find(dest)?.next?.takeUnless { UserCodec.sameNid(it, nodeId) }
        }

        override suspend fun writeNext(nextNid: String, bytes: ByteArray): Boolean =
            writeUser(nextNid, bytes)
    }

    private suspend fun gossipTab(exceptNid: String?) {
        val (tab, targets) = mutex.withLock {
            CtrlMsg.Tab(table.advertise()) to sessions.filterKeys { it != exceptNid }.values.toList()
        }
        targets.forEach { runCatching { it.send(tab) } }
    }

    private suspend fun keepAliveLoop() {
        while (scope.isActive) {
            delay(keepAliveMs)
            val now = System.currentTimeMillis()
            val (alive, dead) = mutex.withLock {
                sessions.values.partition { now - it.lastRxMs <= deadAfterMs }
            }
            alive.forEach { runCatching { it.send(it.nextPing()) } }
            dead.forEach {
                log("silencio de ${it.peerNid?.take(8)}; cierro")
                it.close()
            }
        }
    }

    private suspend fun hiMsg(): CtrlMsg.Hi =
        mutex.withLock { CtrlMsg.Hi(nodeId, selfDepth, treeRoot, table.advertise()) }

    /** Construye el snapshot bajo el mutex: `sessions`/`table` no son concurrentes. */
    private suspend fun publish(action: String) {
        val snap = mutex.withLock {
            LinkSnapshot(
                tree = treeRoot,
                parentId = table.parentId,
                depth = selfDepth,
                neighbors = table.neighbors(),
                routes = table.snapshot(),
                dataOpen = sessions.values.count { it.dataOpen },
                action = action,
            )
        }
        _snapshot.value = snap
    }

    private fun log(message: String) {
        Log.i(TAG, "L2 $message")
        _logs.tryEmit("L2 $message")
    }

    companion object {
        const val CTRL_PORT = 8765
        const val DATA_PORT = 8766
        private const val TAG = "PctMesh"
        private const val CONNECT_MS = 8_000
        private const val DATA_RETRY_MS = 300L
    }
}
