package co.uan.pct.lib.core.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * TCP de usuario sobre la malla: [send] recibe un nid, no una IP.
 * El siguiente salto lo dice la tabla L2 (como OLSR / batman-adv).
 */
interface HopTable {
    suspend fun nextNid(dest: String): String?
    suspend fun writeNext(nextNid: String, bytes: ByteArray): Boolean
}

class MeshSocket(
    private val self: String,
    private val hops: HopTable,
) {
    private val _inbox = MutableSharedFlow<UserMessage>(extraBufferCapacity = 32)
    val inbox: SharedFlow<UserMessage> = _inbox.asSharedFlow()

    suspend fun send(destinationNid: String, payload: ByteArray): Boolean {
        if (UserCodec.sameNid(destinationNid, self)) {
            _inbox.tryEmit(UserMessage(self, payload))
            return true
        }
        return forward(
            UserFrame(self, destinationNid, UserCodec.TTL, payload),
            fromNeighbor = null,
        )
    }

    suspend fun onHop(fromNeighbor: String, frame: UserFrame): Boolean {
        if (UserCodec.sameNid(frame.dst, self)) {
            _inbox.tryEmit(UserMessage(frame.src, frame.payload))
            return true
        }
        if (frame.ttl <= 1) return false
        return forward(frame.hop(), fromNeighbor)
    }

    private suspend fun forward(frame: UserFrame, fromNeighbor: String?): Boolean {
        val next = hops.nextNid(frame.dst) ?: return false
        if (fromNeighbor != null && UserCodec.sameNid(next, fromNeighbor)) return false
        return hops.writeNext(next, UserCodec.encode(frame))
    }
}
