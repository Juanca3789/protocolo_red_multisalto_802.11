package co.uan.pct.lib.core.lab

import co.uan.pct.lib.core.link.CtrlCodec
import co.uan.pct.lib.core.link.CtrlMsg
import co.uan.pct.lib.core.link.RouteTable
import co.uan.pct.lib.core.net.HopTable
import co.uan.pct.lib.core.net.MeshSocket
import co.uan.pct.lib.core.net.UserCodec
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nodo de laboratorio en 127.0.0.1: TCP real de control + datos (puertos efímeros).
 * Sin Android ni Wi‑Fi: el “STA” es un connect al listen del otro.
 */
internal class JvmNode(val id: String) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val ctrlServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val dataServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val ctrlPort: Int = ctrlServer.localPort
    val dataPort: Int = dataServer.localPort
    val table = RouteTable(id)
    private val peers = linkedMapOf<String, LinePeer>()
    private val dataOut = linkedMapOf<String, Socket>()
    private var depth = 0
    private var tree = id

    val mesh = MeshSocket(
        id,
        object : HopTable {
            override suspend fun nextNid(dest: String): String? =
                table.find(dest)?.next?.takeUnless { UserCodec.sameNid(it, id) }

            override suspend fun writeNext(nextNid: String, bytes: ByteArray): Boolean {
                val sock = synchronized(this@JvmNode) { dataOut[nextNid] } ?: return false
                return runCatching {
                    synchronized(sock) {
                        sock.getOutputStream().write(bytes)
                        sock.getOutputStream().flush()
                    }
                    true
                }.getOrDefault(false)
            }
        },
    )

    fun start() {
        scope.launch {
            while (scope.isActive) {
                val sock = runCatching { ctrlServer.accept() }.getOrNull() ?: break
                launch { handleCtrl(sock) }
            }
        }
        scope.launch {
            while (scope.isActive) {
                val sock = runCatching { dataServer.accept() }.getOrNull() ?: break
                launch { handleDataInbound(sock) }
            }
        }
    }

    suspend fun attachTo(other: JvmNode) {
        val sock = withContext(Dispatchers.IO) {
            Socket(InetAddress.getByName("127.0.0.1"), other.ctrlPort)
        }
        handshakeCtrl(sock)
    }

    private suspend fun handleCtrl(sock: Socket) {
        handshakeCtrl(sock)
    }

    private suspend fun handshakeCtrl(sock: Socket) {
        val peer = LinePeer(sock)
        peer.sendRaw("PORT $dataPort")
        val theirPort = peer.readRaw()?.removePrefix("PORT ")?.toIntOrNull()
        peer.send(CtrlMsg.Hi(id, depth, tree, table.advertise()))
        val hi = peer.read() as? CtrlMsg.Hi ?: return
        val child = iAmChild(depth, hi.nid, hi.depth)
        synchronized(this) {
            table.installNeighbor(hi.nid, "127.0.0.1", asParent = child)
            table.mergeFrom(hi.nid, hi.routes)
            if (child) {
                depth = hi.depth + 1
                tree = hi.tree
            }
            peers[hi.nid] = peer
        }
        peer.send(CtrlMsg.Tab(table.advertise()))
        if (child && theirPort != null) {
            openData(hi.nid, theirPort)
        }
        fanoutTab(except = hi.nid)
        scope.launch { ctrlReadLoop(hi.nid, sock, peer) }
    }

    private suspend fun ctrlReadLoop(peerNid: String, sock: Socket, peer: LinePeer) {
        try {
            while (scope.isActive && !sock.isClosed) {
                val msg = withContext(Dispatchers.IO) { peer.read() } ?: break
                if (msg is CtrlMsg.Tab) {
                    val changed = synchronized(this) { table.mergeFrom(peerNid, msg.routes) }
                    if (changed) fanoutTab(except = peerNid)
                }
            }
        } finally {
            synchronized(this) {
                peers.remove(peerNid)
                dataOut.remove(peerNid)?.close()
                table.dropNeighbor(peerNid)
            }
        }
    }

    private suspend fun openData(nid: String, port: Int) {
        val sock = withContext(Dispatchers.IO) {
            Socket(InetAddress.getByName("127.0.0.1"), port).also {
                it.getOutputStream().write(UserCodec.nid16(id))
                it.getOutputStream().flush()
            }
        }
        synchronized(this) { dataOut[nid] = sock }
        scope.launch { readUser(nid, sock) }
    }

    private suspend fun handleDataInbound(sock: Socket) {
        val nidBytes = ByteArray(16)
        withContext(Dispatchers.IO) { sock.getInputStream().readNBytes(nidBytes, 0, 16) }
        val nid = nidBytes.joinToString("") { "%02x".format(it) }
        synchronized(this) { dataOut.putIfAbsent(nid, sock) }
        readUser(nid, sock)
    }

    private suspend fun readUser(nid: String, sock: Socket) {
        try {
            val input = sock.getInputStream()
            while (scope.isActive && !sock.isClosed) {
                val frame = withContext(Dispatchers.IO) { UserCodec.read(input) } ?: break
                mesh.onHop(nid, frame)
            }
        } finally {
            synchronized(this) {
                if (dataOut[nid] === sock) dataOut.remove(nid)
            }
            runCatching { sock.close() }
        }
    }

    private fun fanoutTab(except: String?) {
        val tab = CtrlMsg.Tab(synchronized(this) { table.advertise() })
        val targets = synchronized(this) { peers.filterKeys { it != except }.values.toList() }
        targets.forEach { runCatching { it.send(tab) } }
    }

    private fun iAmChild(selfDepth: Int, peerNid: String, peerDepth: Int): Boolean {
        if (peerDepth != selfDepth) return peerDepth < selfDepth
        return peerNid < id
    }

    override fun close() {
        job.cancel()
        runCatching { ctrlServer.close() }
        runCatching { dataServer.close() }
        synchronized(this) {
            peers.values.forEach { runCatching { it.socket.close() } }
            dataOut.values.forEach { runCatching { it.close() } }
        }
    }
}

private class LinePeer(val socket: Socket) {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    @Synchronized
    fun send(msg: CtrlMsg) {
        sendRaw(CtrlCodec.encode(msg))
    }

    @Synchronized
    fun sendRaw(line: String) {
        writer.write(line)
        writer.write('\n'.code)
        writer.flush()
    }

    fun read(): CtrlMsg? = readRaw()?.let { CtrlCodec.decode(it) }

    fun readRaw(): String? = reader.readLine()
}
