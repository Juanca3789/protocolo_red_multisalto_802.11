package co.uan.pct.lib.core.internal.tcp

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PctFrameCodec {
    const val HEADER_SIZE = 12
    const val MAX_PAYLOAD = 1400
    const val PROTOCOL_VERSION = 1
    private val MAGIC = byteArrayOf(0x50, 0x43, 0x54, 0x31) // PCT1

    fun encode(msgType: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large" }
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(PROTOCOL_VERSION.toByte())
        buf.put(msgType.toByte())
        buf.put(0) // flags
        buf.put(0) // reserved
        buf.putShort(payload.size.toShort())
        buf.putShort(0) // reserved2
        buf.put(payload)
        return buf.array()
    }

    fun decodeHeader(header: ByteArray): Pair<Int, Int> {
        require(header.size == HEADER_SIZE) { "header must be 12 bytes" }
        require(header.copyOfRange(0, 4).contentEquals(MAGIC)) { "bad magic" }
        require(header[4].toInt() and 0xFF == PROTOCOL_VERSION) { "bad version" }
        val msgType = header[5].toInt() and 0xFF
        val payloadLen = ((header[8].toInt() and 0xFF) shl 8) or (header[9].toInt() and 0xFF)
        require(payloadLen <= MAX_PAYLOAD) { "payload_len overflow" }
        return msgType to payloadLen
    }

    fun encodeHello(p: HelloPayload): ByteArray {
        val payload = ByteBuffer.allocate(47).order(ByteOrder.BIG_ENDIAN)
        payload.put(PctUuidCodec.toBytes(p.senderNid))
        payload.put(p.role.toByte())
        payload.put(PctUuidCodec.toBytes(p.parentNid))
        payload.putInt(p.epoch)
        payload.putInt(p.treeVersion)
        payload.put(p.hop.toByte())
        payload.put(p.capabilities.toByte())
        payload.put(p.neighborCount.toByte())
        payload.put(ByteArray(3))
        return encode(PctMsgType.HELLO, payload.array())
    }

    fun decodeHello(payload: ByteArray): HelloPayload {
        require(payload.size >= 47) { "HELLO payload too short" }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val sender = PctUuidCodec.fromBytes(buf.array(), buf.position()).also { buf.position(buf.position() + 16) }
        val role = buf.get().toInt() and 0xFF
        val parent = PctUuidCodec.fromBytes(buf.array(), buf.position()).also { buf.position(buf.position() + 16) }
        val epoch = buf.int
        val treeVersion = buf.int
        val hop = buf.get().toInt() and 0xFF
        val cap = buf.get().toInt() and 0xFF
        val neighborCount = buf.get().toInt() and 0xFF
        return HelloPayload(sender, role, parent, epoch, treeVersion, hop, cap, neighborCount)
    }

    fun encodeJoinCommit(p: JoinCommitPayload): ByteArray {
        val payload = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
        payload.put(PctUuidCodec.toBytes(p.committerNid))
        payload.putInt(p.offerId)
        payload.putInt(p.epoch)
        payload.put(p.via.toByte())
        payload.put(ByteArray(3))
        return encode(PctMsgType.JOIN_COMMIT, payload.array())
    }

    fun decodeJoinCommit(payload: ByteArray): JoinCommitPayload {
        require(payload.size >= 28) { "JOIN_COMMIT too short" }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val nid = PctUuidCodec.fromBytes(buf.array(), 0)
        buf.position(16)
        val offerId = buf.int
        val epoch = buf.int
        val via = buf.get().toInt() and 0xFF
        return JoinCommitPayload(nid, offerId, epoch, via)
    }

    fun encodePing(p: PingPayload): ByteArray {
        val payload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        payload.put(PctUuidCodec.toBytes(p.senderNid))
        payload.putInt(p.seq)
        return encode(PctMsgType.PING, payload.array())
    }

    fun decodePing(payload: ByteArray): PingPayload {
        require(payload.size >= 20) { "PING too short" }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val nid = PctUuidCodec.fromBytes(buf.array(), 0)
        buf.position(16)
        return PingPayload(nid, buf.int)
    }

    fun encodePong(p: PingPayload): ByteArray {
        val payload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        payload.put(PctUuidCodec.toBytes(p.senderNid))
        payload.putInt(p.seq)
        return encode(PctMsgType.PONG, payload.array())
    }

    fun decodePong(payload: ByteArray): PingPayload = decodePing(payload)

    fun encodeDataChannelOpen(p: DataChannelOpenPayload): ByteArray {
        val payload = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN)
        payload.put(PctUuidCodec.toBytes(p.neighborNid))
        payload.putShort(p.dataPort.toShort())
        payload.putInt(p.epoch)
        return encode(PctMsgType.DATA_CHANNEL_OPEN, payload.array())
    }

    fun decodeDataChannelOpen(payload: ByteArray): DataChannelOpenPayload {
        require(payload.size >= 22) { "DATA_CHANNEL_OPEN too short" }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val nid = PctUuidCodec.fromBytes(buf.array(), 0)
        buf.position(16)
        val port = buf.short.toInt() and 0xFFFF
        val epoch = buf.int
        return DataChannelOpenPayload(nid, port, epoch)
    }

    fun encodeDataChannelAck(p: DataChannelAckPayload): ByteArray {
        val payload = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN)
        payload.put(PctUuidCodec.toBytes(p.neighborNid))
        payload.put(p.status.toByte())
        return encode(PctMsgType.DATA_CHANNEL_ACK, payload.array())
    }

    fun decodeDataChannelAck(payload: ByteArray): DataChannelAckPayload {
        require(payload.size >= 17) { "DATA_CHANNEL_ACK too short" }
        val nid = PctUuidCodec.fromBytes(payload, 0)
        val status = payload[16].toInt() and 0xFF
        return DataChannelAckPayload(nid, status)
    }

    fun encodeDataChannelReset(neighborNid: String): ByteArray {
        return encode(PctMsgType.DATA_CHANNEL_RESET, PctUuidCodec.toBytes(neighborNid))
    }

    fun decodeDataChannelReset(payload: ByteArray): String {
        require(payload.size >= 16) { "DATA_CHANNEL_RESET too short" }
        return PctUuidCodec.fromBytes(payload, 0)
    }

    fun encodeTopoUpdate(p: TopoUpdatePayload): ByteArray {
        val entryBytes = 22
        val payload = ByteBuffer.allocate(29 + p.entries.size * entryBytes).order(ByteOrder.BIG_ENDIAN)
        payload.put(PctUuidCodec.toBytes(p.originNid))
        payload.putInt(p.pathSeq)
        payload.putInt(p.epoch)
        payload.put(p.ttl.toByte())
        payload.put(p.entries.size.toByte())
        payload.put(ByteArray(3))
        for (e in p.entries) {
            payload.put(PctUuidCodec.toBytes(e.destNid))
            payload.put(e.hopCount.toByte())
            payload.put(e.status.toByte())
            payload.putInt(e.pathSeq)
        }
        return encode(PctMsgType.TOPO_UPDATE, payload.array())
    }

    fun decodeTopoUpdate(payload: ByteArray): TopoUpdatePayload {
        require(payload.size >= 29) { "TOPO_UPDATE too short" }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val origin = PctUuidCodec.fromBytes(payload, 0)
        buf.position(16)
        val pathSeq = buf.int
        val epoch = buf.int
        val ttl = buf.get().toInt() and 0xFF
        val count = buf.get().toInt() and 0xFF
        buf.position(29)
        val entries = ArrayList<TopoRouteEntry>(count)
        repeat(count) {
            val dest = PctUuidCodec.fromBytes(payload, buf.position())
            buf.position(buf.position() + 16)
            val hop = buf.get().toInt() and 0xFF
            val status = buf.get().toInt() and 0xFF
            val entryPathSeq = buf.int
            entries.add(TopoRouteEntry(dest, hop, status, entryPathSeq))
        }
        return TopoUpdatePayload(origin, pathSeq, epoch, ttl, entries)
    }

    fun encodeUserData(p: UserDataPayload): ByteArray {
        require(p.trace.size <= 8) { "trace_count max 8" }
        require(p.userData.size <= 1024) { "user_data max 1024" }
        val headerLen = 58 + p.trace.size * 16
        val payload = ByteBuffer.allocate(headerLen + p.userData.size).order(ByteOrder.BIG_ENDIAN)
        payload.put(PctUuidCodec.toBytes(p.msgId))
        payload.put(PctUuidCodec.toBytes(p.srcNid))
        payload.put(PctUuidCodec.toBytes(p.dstNid))
        payload.putInt(p.sessionEpoch)
        payload.put(p.hopLimit.toByte())
        payload.put(p.trace.size.toByte())
        payload.putShort(p.userData.size.toShort())
        payload.putShort(0)
        for (t in p.trace) {
            payload.put(PctUuidCodec.toBytes(t))
        }
        payload.put(p.userData)
        return encode(PctMsgType.DATA, payload.array())
    }

    fun decodeUserData(payload: ByteArray): UserDataPayload {
        require(payload.size >= 58) { "DATA payload too short" }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val msgId = PctUuidCodec.fromBytes(payload, 0)
        val src = PctUuidCodec.fromBytes(payload, 16)
        val dst = PctUuidCodec.fromBytes(payload, 32)
        buf.position(48)
        val sessionEpoch = buf.int
        val hopLimit = buf.get().toInt() and 0xFF
        val traceCount = buf.get().toInt() and 0xFF
        val dataLen = buf.short.toInt() and 0xFFFF
        buf.position(58)
        val trace = ArrayList<String>(traceCount)
        repeat(traceCount) {
            trace.add(PctUuidCodec.fromBytes(payload, buf.position()))
            buf.position(buf.position() + 16)
        }
        val userBytes = ByteArray(dataLen)
        if (dataLen > 0) {
            buf.get(userBytes)
        }
        return UserDataPayload(msgId, src, dst, sessionEpoch, hopLimit, trace, userBytes)
    }

    /** Maps role string to wire enum. */
    fun roleToWire(role: String): Int = when (role.uppercase()) {
        "ROOT" -> 3
        "BRIDGE" -> 2
        "LEAF", "CHILD", "MEMBER" -> 1
        else -> 0
    }

    fun roleFromWire(wire: Int): String = when (wire) {
        3 -> "ROOT"
        2 -> "BRIDGE"
        1 -> "LEAF"
        else -> "ISLAND"
    }
}
