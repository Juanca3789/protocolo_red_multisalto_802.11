package co.uan.pct.lib.core.link

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import co.uan.pct.lib.core.net.HopTable
import co.uan.pct.lib.core.net.MeshSocket
import co.uan.pct.lib.core.net.UserCodec
import co.uan.pct.lib.core.physical.PhysicalLayer
import co.uan.pct.lib.core.physical.Role
import co.uan.pct.lib.core.physical.ServiceStructure
import co.uan.pct.lib.core.physical.orientEdge
import co.uan.pct.lib.core.physical.parsePctUuid
import co.uan.pct.lib.core.physical.pctHex
import co.uan.pct.lib.core.physical.selectPeerToJoin
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.uuid.ExperimentalUuidApi
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

@OptIn(ExperimentalUuidApi::class)
class LinkLayer(
    private val nodeId: String,
    private val physical: PhysicalLayer,
    context: Context,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
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
    private val walks = EventWalks()
    private val walkTimers = mutableMapOf<String, Job>()
    private val investigating = mutableSetOf<String>()
    private val probed = mutableSetOf<String>()
    private val foreign = linkedMapOf<String, ServiceStructure>()
    private val radioSight = linkedMapOf<String, ServiceStructure>()
    private val whoHits = mutableMapOf<String, Boolean>()
    private val goingFor = mutableMapOf<String, String>()
    private val armLaunched = mutableSetOf<String>()

    private val _snapshot = MutableStateFlow(LinkSnapshot(tree = nodeId))
    val snapshot: StateFlow<LinkSnapshot> = _snapshot.asStateFlow()
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private var treeRoot: String = nodeId
    private var selfDepth: Int = 0
    private var ctrlServer: ServerSocket? = null
    private var dataServer: ServerSocket? = null
    private var started = false
    private var staClientJob: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch { acceptLoop(CTRL_PORT) { onCtrlSocket(it, inbound = true) } }
        scope.launch { acceptLoop(DATA_PORT) { onDataSocket(it) } }
        scope.launch { keepAliveLoop() }
        scope.launch { watchPhysical() }
        log("enlace escucha :$CTRL_PORT / :$DATA_PORT")
        publish("enlace listo")
    }

    fun close() {
        started = false
        job.cancel()
        runCatching { ctrlServer?.close() }
        runCatching { dataServer?.close() }
        sessions.values.forEach { it.close() }
        sessions.clear()
        dataOut.values.forEach { runCatching { it.close() } }
        dataOut.clear()
    }

    fun sendUser(destinationNid: String, payload: ByteArray) {
        scope.launch { mesh.send(destinationNid, payload) }
    }

    private suspend fun watchPhysical() {
        var lastSta = false
        physical.snapshot.collect { snap ->
            if (snap.staConnected && !lastSta) {
                lastSta = true
                val net = physical.activeStaNetwork
                if (net != null) {
                    val gw = staGateway(connectivity, net, physical.staBssid)
                    log("asociado al padre → TCP $gw:$CTRL_PORT")
                    staClientJob?.cancel()
                    staClientJob = scope.launch { connectCtrl(gw) }
                }
            }
            if (!snap.staConnected) lastSta = false
        }
    }

    private suspend fun handleSightings(seen: List<ServiceStructure>) {
        mutex.withLock {
            radioSight.keys.retainAll(seen.map { it.nid.pctHex().take(8) }.toSet())
            seen.forEach { radioSight[it.nid.pctHex().take(8)] = it }
            if (table.isOrphan()) return
        }
        for (peer in seen) {
            val nid = peer.nid.pctHex()
            val kind = mutex.withLock {
                classifySight(
                    nid = nid,
                    table = table,
                    probing = nid.take(8) in investigating,
                    probed = nid.take(8) in probed,
                    someoneKnows = whoHits[nid.take(8)] == true || table.known(nid),
                    claimed = walks.claimed(nid) || nid.take(8) in goingFor,
                )
            }
            when (kind) {
                SightKind.Ours -> Unit
                SightKind.Wait -> Unit
                SightKind.Probe -> scope.launch { probe(nid, peer) }
                SightKind.OtherTree -> scope.launch { onOtherTree(peer) }
            }
        }
    }

    private suspend fun probe(nid: String, peer: ServiceStructure) {
        val short = nid.take(8)
        val (walk, started) = mutex.withLock {
            if (!investigating.add(short)) return
            foreign[short] = peer
            log("investigar $short (DFS por vecinos, no asumir arranque mal)")
            publish("pregunto por $short")
            walks.begin(
                kind = WalkKind.Who,
                target = nid,
                origin = nodeId,
                hops = 0,
                neighbors = table.neighbors(),
                parentId = table.parentId,
                hopOf = table::hopOf,
            )
        }
        if (started) stepWalk(walk.eid)
    }

    private suspend fun onOtherTree(peer: ServiceStructure) {
        val nid = peer.nid.pctHex()
        val short = nid.take(8)
        val (walk, started) = mutex.withLock {
            foreign[short] = peer
            probed.add(short)
            log("otro árbol: $short ssid=${peer.goSsid}")
            publish("otro árbol $short")
            walks.begin(
                kind = WalkKind.See,
                target = nid,
                origin = nodeId,
                hops = 0,
                neighbors = table.neighbors(),
                parentId = table.parentId,
                hopOf = table::hopOf,
            )
        }
        if (started) stepWalk(walk.eid)
        considerArm(peer)
    }

    private suspend fun considerArm(peer: ServiceStructure) {
        val nid = peer.nid.pctHex()
        val short = nid.take(8)
        delay(ELECT_MS)
        val decision = mutex.withLock {
            volunteer(
                hasRadioAccess = short in radioSight,
                selfNid = nodeId,
                claimedBy = goingFor[short],
            )
        }
        if (decision == Volunteer.Sit) {
            log("sin radio a $short; el DFS sigue por vecinos")
            return
        }
        if (decision == Volunteer.Yield) {
            log("otro brazo más cercano ya va a $short")
            return
        }
        val first = mutex.withLock { armLaunched.add(short) }
        if (!first) return
        mutex.withLock { goingFor[short] = nodeId }
        val (going, started) = mutex.withLock {
            walks.begin(
                kind = WalkKind.Going,
                target = nid,
                origin = nodeId,
                hops = 0,
                neighbors = table.neighbors(),
                parentId = table.parentId,
                hopOf = table::hopOf,
            ).also { (walk, _) -> walks.markClaimed(walk.eid, nodeId) }
        }
        if (started) stepWalk(going.eid)
        delay(ELECT_MS)
        val stillMine = mutex.withLock {
            val claim = goingFor[short] ?: nodeId
            claimWinner(claim, nodeId) == nodeId
        }
        if (!stillMine) {
            log("merge: gana brazo ${goingFor[short]?.take(8)}")
            return
        }
        executeArm(peer)
    }

    private suspend fun executeArm(peer: ServiceStructure) {
        val plan = mutex.withLock { planArm(table.parentId != null, table.childCount()) }
        when (plan) {
            ArmPlan.Infiltrate -> {
                publish("brazo libre → ${peer.goSsid}")
                runCatching { physical.connectToParent(peer) }
            }
            ArmPlan.DetachAndInfiltrate -> {
                publish("hoja: suelta padre e infiltra")
                physical.dropSta()
                delay(400)
                runCatching { physical.connectToParent(peer) }
            }
            ArmPlan.FreeArm -> {
                val creds = physical.parentCredentials()
                if (creds == null) {
                    log("reorg local: sin PSK del padre")
                    publish("reorg: falta PSK padre")
                    return
                }
                publish("reorg local: hijos suben al padre")
                mutex.withLock {
                    table.childIds().forEach { child ->
                        runCatching { sessions[child]?.send(CtrlMsg.Climb(creds.ssid, creds.psk)) }
                    }
                }
                delay(CLIMB_WAIT_MS)
                val stillMine = mutex.withLock {
                    val short = peer.nid.pctHex().take(8)
                    claimWinner(goingFor[short] ?: nodeId, nodeId) == nodeId
                }
                if (!stillMine) return
                physical.dropSta()
                delay(400)
                runCatching { physical.connectToParent(peer) }
            }
        }
    }

    private suspend fun stepWalk(eid: String) {
        val next = mutex.withLock { walks.takeNext(eid) }
        if (next == null) {
            finishWalk(eid)
            return
        }
        val token = mutex.withLock { tokenFor(walks.get(eid) ?: return) }
        val sent = sendTo(next, token)
        if (!sent) {
            mutex.withLock { walks.onBranchDone(eid, next, known = false) }
            stepWalk(eid)
            return
        }
        walkTimers[eid]?.cancel()
        walkTimers[eid] = scope.launch {
            delay(WALK_HOP_MS)
            val stillWaiting = mutex.withLock { walks.get(eid)?.waiting == next }
            if (stillWaiting) {
                mutex.withLock { walks.onBranchDone(eid, next, known = false) }
                stepWalk(eid)
            }
        }
    }

    private fun tokenFor(walk: WalkState): CtrlMsg {
        val hops = walk.hops + 1
        val ttl = (WALK_TTL - hops).coerceAtLeast(1)
        return when (walk.kind) {
            WalkKind.Who -> CtrlMsg.Who(walk.eid, walk.target, ttl, hops, walk.origin)
            WalkKind.See -> {
                val peer = foreign[walk.target.take(8)]
                CtrlMsg.See(
                    nid = walk.target,
                    ssid = peer?.goSsid.orEmpty(),
                    psk = peer?.goPsk.orEmpty(),
                    depth = peer?.depth ?: 0,
                    hops = hops,
                    origin = walk.origin,
                )
            }
            WalkKind.Going -> CtrlMsg.Going(
                nid = walk.target,
                by = walk.claimedBy ?: nodeId,
                hops = hops,
                origin = walk.origin,
            )
        }
    }

    private suspend fun finishWalk(eid: String) {
        walkTimers.remove(eid)?.cancel()
        val walk = mutex.withLock { walks.get(eid) } ?: return
        if (walk.kind == WalkKind.Who && walk.known) {
            mutex.withLock { whoHits[walk.target.take(8)] = true }
        }
        if (!walk.isOrigin) {
            val back = walk.from
            val reply = when (walk.kind) {
                WalkKind.Who -> CtrlMsg.WhoR(
                    walk.eid,
                    walk.target,
                    walk.known,
                    mutex.withLock { table.find(walk.target)?.hops ?: walk.hops },
                )
                WalkKind.See, WalkKind.Going -> CtrlMsg.Merge(walk.eid)
            }
            if (back != null) sendTo(back, reply)
            mutex.withLock { walks.remove(eid) }
            return
        }
        if (walk.kind == WalkKind.Who) {
            val short = walk.target.take(8)
            val known = mutex.withLock {
                investigating.remove(short)
                probed.add(short)
                walk.known || table.known(walk.target) || whoHits[short] == true
            }
            mutex.withLock { walks.remove(eid) }
            if (known) {
                log("$short está en el árbol — anuncio residual")
                return
            }
            val peer = mutex.withLock { foreign[short] } ?: return
            onOtherTree(peer)
            return
        }
        mutex.withLock { walks.remove(eid) }
    }

    private suspend fun onWho(session: LinkSession, msg: CtrlMsg.Who) {
        val from = session.peerNid ?: return
        val known = mutex.withLock { table.known(msg.nid) }
        if (known) {
            val hops = mutex.withLock { table.find(msg.nid)?.hops ?: 0 }
            runCatching { session.send(CtrlMsg.WhoR(msg.eid, msg.nid, true, hops)) }
            return
        }
        if (msg.ttl <= 1) {
            runCatching { session.send(CtrlMsg.WhoR(msg.eid, msg.nid, false, msg.hops)) }
            return
        }
        handleArrival(WalkKind.Who, msg.nid, msg.origin, from, msg.hops)
    }

    private suspend fun onWhoR(session: LinkSession, msg: CtrlMsg.WhoR) {
        val from = session.peerNid ?: return
        if (msg.known) mutex.withLock { whoHits[msg.nid.take(8)] = true }
        mutex.withLock { walks.onBranchDone(msg.eid, from, msg.known) }
        stepWalk(msg.eid)
    }

    private suspend fun onSee(session: LinkSession, msg: CtrlMsg.See) {
        val from = session.peerNid ?: return
        val peer = peerFromSee(msg)
        mutex.withLock {
            if (table.known(msg.nid)) {
                whoHits[msg.nid.take(8)] = true
            } else if (peer != null) {
                foreign[msg.nid.take(8)] = peer
            }
        }
        if (mutex.withLock { table.known(msg.nid) }) {
            runCatching {
                session.send(CtrlMsg.WhoR(eventId(WalkKind.Who, msg.nid), msg.nid, true, 0))
            }
            return
        }
        handleArrival(WalkKind.See, msg.nid, msg.origin, from, msg.hops)
        if (peer != null) scope.launch { considerArm(peer) }
    }

    private suspend fun onGoing(session: LinkSession, msg: CtrlMsg.Going) {
        val from = session.peerNid ?: return
        mutex.withLock {
            val short = msg.nid.take(8)
            val prev = goingFor[short]
            goingFor[short] = if (prev == null) msg.by else claimWinner(prev, msg.by)
        }
        handleArrival(WalkKind.Going, msg.nid, msg.origin, from, msg.hops)
    }

    private suspend fun onMerge(session: LinkSession, msg: CtrlMsg.Merge) {
        val from = session.peerNid ?: return
        log("merge ${msg.eid} con ${from.take(8)} (misma onda, no se duplica)")
        mutex.withLock { walks.onBranchDone(msg.eid, from, known = false) }
        stepWalk(msg.eid)
    }

    private suspend fun handleArrival(
        kind: WalkKind,
        target: String,
        origin: String,
        from: String,
        hops: Int,
    ) {
        val arrival = mutex.withLock {
            walks.arrive(
                kind = kind,
                target = target,
                origin = origin,
                from = from,
                hops = hops,
                neighbors = table.neighbors(),
                parentId = table.parentId,
                hopOf = table::hopOf,
            )
        }
        when (arrival) {
            WalkArrival.Loop -> sendTo(from, CtrlMsg.Merge(eventId(kind, target)))
            is WalkArrival.Keep -> {
                log("choque ${eventId(kind, target)}: nos quedamos (más cerca)")
                sendTo(from, CtrlMsg.Merge(arrival.state.eid))
            }
            is WalkArrival.Yield -> {
                log("choque ${arrival.state.eid}: cede a onda más cercana")
                arrival.oldFrom?.let { sendTo(it, CtrlMsg.Merge(arrival.state.eid)) }
                stepWalk(arrival.state.eid)
            }
            is WalkArrival.Fresh -> stepWalk(arrival.state.eid)
        }
    }

    private suspend fun sendTo(nid: String, msg: CtrlMsg): Boolean {
        val session = mutex.withLock { sessions[nid] }
        if (session == null) return false
        return runCatching { session.send(msg) }.isSuccess
    }

    private suspend fun gossipTab(exceptNid: String?) {
        val tab = mutex.withLock { CtrlMsg.Tab(table.advertise()) }
        val nids = mutex.withLock { table.neighbors().filter { it != exceptNid } }
        for (nid in nids) sendTo(nid, tab)
    }

    private suspend fun connectCtrl(address: InetAddress) {
        runCatching {
            val socket = Socket()
            physical.activeStaNetwork?.let { net ->
                net.bindSocket(socket)
            }
            socket.connect(java.net.InetSocketAddress(address, CTRL_PORT), 8_000)
            onCtrlSocket(socket, inbound = false)
        }.onFailure { log("connect :$CTRL_PORT ${it.message}") }
    }

    private suspend fun acceptLoop(port: Int, onSock: suspend (Socket) -> Unit) {
        val server = withContext(Dispatchers.IO) { ServerSocket(port) }
        if (port == CTRL_PORT) ctrlServer = server else dataServer = server
        while (scope.isActive) {
            val sock = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch { onSock(sock) }
        }
    }

    private suspend fun onCtrlSocket(socket: Socket, inbound: Boolean) {
        val ip = socket.inetAddress.hostAddress ?: return
        val session = LinkSession(socket, ip)
        log("TCP ${if (inbound) "in" else "out"} $ip:$CTRL_PORT")
        runCatching { session.send(hiMsg()) }
        try {
            while (scope.isActive && !socket.isClosed) {
                val msg = withContext(Dispatchers.IO) {
                    runCatching { session.read() }.getOrNull()
                } ?: break
                handleMsg(session, msg)
            }
        } catch (t: Throwable) {
            log("TCP $ip: ${t.message}")
        } finally {
            val nid = session.peerNid
            session.close()
            if (nid != null) {
                mutex.withLock {
                    sessions.remove(nid)
                    dataOut.remove(nid)?.let { runCatching { it.close() } }
                    table.dropNeighbor(nid)
                    if (table.parentId == null) {
                        treeRoot = nodeId
                        selfDepth = 0
                    }
                }
                log("vecino caído ${nid.take(8)}")
                gossipTab(exceptNid = nid)
                publish("vecino down")
            }
        }
    }

    private suspend fun onDataSocket(socket: Socket) {
        val remote = socket.inetAddress ?: return
        val ip = remote.hostAddress ?: return
        val nid = mutex.withLock {
            val session = sessions.values.firstOrNull { it.sameHost(remote) }
            session?.dataOpen = true
            session?.peerNid?.also { dataOut.putIfAbsent(it, socket) }
        }
        publish("datos :$DATA_PORT de $ip")
        if (nid != null) {
            readUser(nid, socket)
            return
        }
        runCatching { socket.close() }
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
                if (dataOut[nid] === socket) dataOut.remove(nid)
            }
            runCatching { socket.close() }
        }
    }

    private suspend fun writeUser(nextNid: String, bytes: ByteArray): Boolean {
        val sock = mutex.withLock { dataOut[nextNid] } ?: connectData(nextNid) ?: return false
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

    private suspend fun connectData(nid: String): Socket? {
        val session = mutex.withLock { sessions[nid] } ?: return null
        return runCatching {
            val sock = Socket()
            val bindSta = mutex.withLock { table.parentId == nid }
            if (bindSta) physical.activeStaNetwork?.bindSocket(sock)
            sock.connect(InetSocketAddress(session.remoteAddress, DATA_PORT), 5_000)
            mutex.withLock {
                dataOut[nid] = sock
                session.dataOpen = true
            }
            scope.launch { readUser(nid, sock) }
            publish(":$DATA_PORT abierto ${nid.take(8)}")
            sock
        }.getOrNull()
    }

    private inner class LinkHops : HopTable {
        override suspend fun nextNid(dest: String): String? = mutex.withLock {
            table.find(dest)?.next?.takeUnless { UserCodec.sameNid(it, nodeId) }
        }

        override suspend fun writeNext(nextNid: String, bytes: ByteArray): Boolean =
            writeUser(nextNid, bytes)
    }

    private suspend fun handleMsg(session: LinkSession, msg: CtrlMsg) {
        when (msg) {
            is CtrlMsg.Hi -> onHi(session, msg)
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
            is CtrlMsg.Who -> onWho(session, msg)
            is CtrlMsg.WhoR -> onWhoR(session, msg)
            is CtrlMsg.See -> onSee(session, msg)
            is CtrlMsg.Climb -> onClimb(msg)
            is CtrlMsg.Going -> onGoing(session, msg)
            is CtrlMsg.Merge -> onMerge(session, msg)
        }
    }

    private suspend fun onHi(session: LinkSession, hi: CtrlMsg.Hi) {
        val iAmChild = mutex.withLock {
            session.peerNid = hi.nid
            session.peerDepth = hi.depth
            session.peerTree = hi.tree
            sessions[hi.nid] = session
            val child = shouldBeChild(hi)
            table.installNeighbor(hi.nid, session.remoteIp, asParent = child)
            table.mergeFrom(hi.nid, hi.routes)
            if (child) {
                table.setParent(hi.nid)
                treeRoot = hi.tree
                selfDepth = hi.depth + 1
            } else if (physical.activeStaNetwork != null &&
                physical.snapshot.value.staSsid != null
            ) {
                physical.dropSta()
                selfDepth = 0
                treeRoot = nodeId
            }
            child
        }
        log(
            "arista ${if (iAmChild) "hijo" else "padre"} ↔ ${hi.nid.take(8)} " +
                "tree=${hi.tree.take(8)} rutas=${hi.routes.size}",
        )
        runCatching { session.send(CtrlMsg.Tab(mutex.withLock { table.advertise() })) }
        gossipTab(exceptNid = hi.nid)
        openData(session)
        publish(if (iAmChild) "hijo de ${hi.nid.take(8)}" else "padre de ${hi.nid.take(8)}")
    }

    private fun shouldBeChild(hi: CtrlMsg.Hi): Boolean {
        val selfUuid = physical.nodeConfig.nodeId
        val peerUuid = parsePctUuid(hi.nid) ?: return hi.nid < nodeId
        val peer = ServiceStructure(
            nid = peerUuid,
            role = Role.BRIDGE,
            depth = hi.depth,
            ctrlPort = CTRL_PORT,
            goSsid = "x",
            goPsk = "xxxxxxxx",
            childCount = 0,
        )
        return orientEdge(selfUuid, selfDepth, peer) ==
            co.uan.pct.lib.core.physical.EdgeRole.CHILD
    }

    private suspend fun onClimb(msg: CtrlMsg.Climb) {
        log("reorg local: subir al GO ${msg.ssid}")
        val nid = parsePctUuid("0".repeat(32)) ?: return
        val target = ServiceStructure(
            nid = nid,
            role = Role.BRIDGE,
            depth = 0,
            ctrlPort = CTRL_PORT,
            goSsid = msg.ssid,
            goPsk = msg.psk,
            childCount = 0,
        )
        physical.dropSta()
        delay(300)
        runCatching { physical.connectToParent(target) }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun peerFromSee(msg: CtrlMsg.See): ServiceStructure? {
        val uuid = parsePctUuid(msg.nid) ?: return null
        return ServiceStructure(
            nid = uuid,
            role = Role.BRIDGE,
            depth = msg.depth,
            ctrlPort = CTRL_PORT,
            goSsid = msg.ssid,
            goPsk = msg.psk,
            childCount = 0,
        )
    }

    private fun openData(session: LinkSession) {
        val nid = session.peerNid ?: return
        scope.launch { connectData(nid) }
    }

    private suspend fun keepAliveLoop() {
        while (scope.isActive) {
            delay(5_000)
            val now = System.currentTimeMillis()
            val dead = mutex.withLock {
                sessions.values.forEach { runCatching { it.send(it.nextPing()) } }
                sessions.filter { now - it.value.lastRxMs > 15_000 }.keys.toList()
            }
            for (nid in dead) {
                mutex.withLock {
                    sessions.remove(nid)?.close()
                    dataOut.remove(nid)?.let { runCatching { it.close() } }
                    table.dropNeighbor(nid)
                }
                log("timeout ${nid.take(8)}")
            }
            if (dead.isNotEmpty()) {
                gossipTab(exceptNid = null)
                publish("timeout vecinos")
            }
        }
    }

    private fun hiMsg(): CtrlMsg.Hi {
        val routes = table.advertise()
        return CtrlMsg.Hi(nodeId, selfDepth, treeRoot, routes)
    }

    private fun publish(action: String) {
        _snapshot.value = LinkSnapshot(
            tree = treeRoot,
            parentId = table.parentId,
            depth = selfDepth,
            neighbors = table.neighbors(),
            routes = table.snapshot(),
            foreign = foreign.keys.toList(),
            dataOpen = sessions.values.count { it.dataOpen },
            action = action,
        )
    }

    private fun log(message: String) {
        Log.i(TAG, "L2 $message")
        _logs.tryEmit("L2 $message")
    }

    private companion object {
        const val TAG = "PctMesh"
        const val CTRL_PORT = 8765
        const val DATA_PORT = 8766
        const val WALK_HOP_MS = 700L
        const val WALK_TTL = 7
        const val ELECT_MS = 400L
        const val CLIMB_WAIT_MS = 8_000L
    }
}
