package co.uan.pct.lib.core.internal.route

import co.uan.pct.lib.core.api.DataChannelState
import co.uan.pct.lib.core.api.NeighborSnapshot
import co.uan.pct.lib.core.api.RouteSnapshot
import co.uan.pct.lib.core.internal.link.NeighborRegistry
import co.uan.pct.lib.core.internal.tcp.PctFrameCodec
import co.uan.pct.lib.core.internal.tcp.PctMsgType
import co.uan.pct.lib.core.internal.tcp.TopoUpdatePayload
import co.uan.pct.lib.core.internal.tcp.UserDataPayload
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RouteOrchestrator(
    private val selfNid: () -> String,
    private val selfRole: () -> String,
    private val selfHop: () -> Int,
    private val registry: NeighborRegistry,
    private val log: (String) -> Unit,
    private val emitUserMessage: (fromNid: String, text: String) -> Unit,
) {
    private val table = RoutingTable(selfNid)
    private var pathSeq = 1
    private val epoch = 1

    private val _routes = MutableStateFlow(RouteSnapshot())
    val routes: StateFlow<RouteSnapshot> = _routes.asStateFlow()

    private val _neighbors = MutableStateFlow(NeighborSnapshot())
    val neighbors: StateFlow<NeighborSnapshot> = _neighbors.asStateFlow()

    suspend fun refreshFromRegistry() {
        val all = registry.all()
        table.rebuildDirect(all)
        _neighbors.value = registry.snapshot()
        _routes.value = table.snapshot()
    }

    suspend fun broadcastTopo(ctrlWrite: suspend (neighborNid: String, frame: ByteArray) -> Unit) {
        val entries = table.topoEntries()
        if (entries.isEmpty()) return
        val payload = TopoUpdatePayload(
            originNid = selfNid(),
            pathSeq = pathSeq++,
            epoch = epoch,
            ttl = 3,
            entries = entries,
        )
        val frame = PctFrameCodec.encodeTopoUpdate(payload)
        for (n in registry.all()) {
            if (n.ctrlSocket != null && !n.ctrlSocket!!.isClosed) {
                runCatching { ctrlWrite(n.neighborNid, frame) }
            }
        }
        log("[L3] TOPO_UPDATE broadcast entries=${entries.size}")
    }

    suspend fun handleTopoUpdate(fromNid: String, payload: TopoUpdatePayload) {
        val neighbor = registry.get(fromNid) ?: return
        table.merge(fromNid, payload.entries, neighborHop = 1, originLocalIp = neighbor.localIp)
        _routes.value = table.snapshot()
        log("[L3] TOPO_UPDATE from ${fromNid.take(8)}… entries=${payload.entries.size}")
    }

    suspend fun handleUserData(payload: UserDataPayload) {
        val self = selfNid()
        if (payload.dstNid == self) {
            val text = payload.userData.decodeToString()
            log("[L3] UserMessage from ${payload.srcNid.take(8)}…")
            emitUserMessage(payload.srcNid, text)
            return
        }
        if (payload.hopLimit <= 0) {
            log("[L3] DATA drop hop_limit=0 dst=${payload.dstNid.take(8)}…")
            return
        }
        if (payload.trace.contains(self)) {
            log("[L3] DATA drop loop detected")
            return
        }
        forward(payload)
    }

    private suspend fun forward(payload: UserDataPayload) {
        val next = table.resolveNextHop(payload.dstNid)
        if (next == null) {
            log("[L3] DATA no route to ${payload.dstNid.take(8)}…")
            return
        }
        val (nextHopNid, _) = next
        val neighbor = registry.get(nextHopNid)
        if (neighbor == null || neighbor.dataSocket == null ||
            neighbor.dataChannelState != DataChannelState.OPEN ||
            neighbor.dataSocket!!.isClosed
        ) {
            log("[L3] DATA next-hop data channel not OPEN (${nextHopNid.take(8)}…)")
            return
        }
        val trace = payload.trace + selfNid()
        val forwarded = UserDataPayload(
            msgId = payload.msgId,
            srcNid = payload.srcNid,
            dstNid = payload.dstNid,
            sessionEpoch = payload.sessionEpoch,
            hopLimit = payload.hopLimit - 1,
            trace = trace,
            userData = payload.userData,
        )
        val frame = PctFrameCodec.encodeUserData(forwarded)
        runCatching {
            neighbor.dataSocket!!.writeRaw(frame)
            log("[L3] forward DATA → ${nextHopNid.take(8)}… dst=${payload.dstNid.take(8)}…")
        }.onFailure { e ->
            log("[L3] forward DATA failed: ${e.message}")
        }
    }

    suspend fun sendUser(destinationNid: String, payload: ByteArray) {
        val self = selfNid()
        if (destinationNid == self) {
            emitUserMessage(self, payload.decodeToString())
            return
        }
        val direct = registry.get(destinationNid)
        if (direct != null && direct.dataChannelState == DataChannelState.OPEN &&
            direct.dataSocket != null && !direct.dataSocket!!.isClosed
        ) {
            writeUserData(direct.neighborNid, destinationNid, payload, hopLimit = 7)
            return
        }
        val next = table.resolveNextHop(destinationNid)
        if (next == null) {
            log("[L3] sendUser: sin ruta a ${destinationNid.take(8)}…")
            return
        }
        val (nextHopNid, _) = next
        val neighbor = registry.get(nextHopNid)
        if (neighbor == null || neighbor.dataChannelState != DataChannelState.OPEN) {
            log("[L3] sendUser: canal datos no OPEN hacia ${nextHopNid.take(8)}…")
            return
        }
        writeUserData(nextHopNid, destinationNid, payload, hopLimit = 7)
    }

    private suspend fun writeUserData(
        viaNid: String,
        destinationNid: String,
        payload: ByteArray,
        hopLimit: Int,
    ) {
        val neighbor = registry.get(viaNid) ?: return
        val socket = neighbor.dataSocket ?: return
        val msgId = UUID.randomUUID().toString().replace("-", "")
        val userPayload = UserDataPayload(
            msgId = msgId,
            srcNid = selfNid(),
            dstNid = destinationNid,
            sessionEpoch = epoch,
            hopLimit = hopLimit,
            trace = emptyList(),
            userData = payload,
        )
        val frame = PctFrameCodec.encodeUserData(userPayload)
        runCatching {
            socket.writeRaw(frame)
            log("[L3] sendUser → via ${viaNid.take(8)}… dst=${destinationNid.take(8)}…")
        }.onFailure { e ->
            log("[L3] sendUser failed: ${e.message}")
        }
    }
}
