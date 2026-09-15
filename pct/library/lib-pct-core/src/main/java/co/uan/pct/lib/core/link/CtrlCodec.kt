package co.uan.pct.lib.core.link

import java.util.Base64

sealed interface CtrlMsg {
    data class Hi(
        val nid: String,
        val depth: Int,
        val tree: String,
        val routes: List<Pair<String, Int>>,
    ) : CtrlMsg

    data class Tab(val routes: List<Pair<String, Int>>) : CtrlMsg
    data class Ping(val seq: Int) : CtrlMsg
    data class Pong(val seq: Int) : CtrlMsg
    data class Who(
        val eid: String,
        val nid: String,
        val ttl: Int,
        val hops: Int,
        val origin: String,
    ) : CtrlMsg

    data class WhoR(val eid: String, val nid: String, val known: Boolean, val hops: Int) : CtrlMsg
    data class See(
        val nid: String,
        val ssid: String,
        val psk: String,
        val depth: Int,
        val hops: Int,
        val origin: String,
    ) : CtrlMsg

    data class Climb(val ssid: String, val psk: String) : CtrlMsg
    data class Going(
        val nid: String,
        val by: String,
        val hops: Int,
        val origin: String,
    ) : CtrlMsg

    data class Merge(val eid: String) : CtrlMsg
}

object CtrlCodec {
    fun encode(msg: CtrlMsg): String = when (msg) {
        is CtrlMsg.Hi ->
            "HI ${msg.nid} ${msg.depth} ${msg.tree} ${packRoutes(msg.routes)}"
        is CtrlMsg.Tab ->
            "TAB ${packRoutes(msg.routes)}"
        is CtrlMsg.Ping -> "PING ${msg.seq}"
        is CtrlMsg.Pong -> "PONG ${msg.seq}"
        is CtrlMsg.Who ->
            "WHO ${msg.eid} ${msg.nid} ${msg.ttl} ${msg.hops} ${msg.origin}"
        is CtrlMsg.WhoR ->
            "WHOR ${msg.eid} ${msg.nid} ${if (msg.known) 1 else 0} ${msg.hops}"
        is CtrlMsg.See ->
            "SEE ${msg.nid} ${b64(msg.ssid)} ${b64(msg.psk)} ${msg.depth} ${msg.hops} ${msg.origin}"
        is CtrlMsg.Climb ->
            "CLIMB ${b64(msg.ssid)} ${b64(msg.psk)}"
        is CtrlMsg.Going -> "GOING ${msg.nid} ${msg.by} ${msg.hops} ${msg.origin}"
        is CtrlMsg.Merge -> "MERGE ${msg.eid}"
    }

    fun decode(line: String): CtrlMsg? {
        val p = line.trim().split(' ')
        if (p.isEmpty()) return null
        return runCatching {
            when (p[0]) {
                "HI" -> CtrlMsg.Hi(p[1], p[2].toInt(), p[3], unpackRoutes(p.getOrElse(4) { "" }))
                "TAB" -> CtrlMsg.Tab(unpackRoutes(p.getOrElse(1) { "" }))
                "PING" -> CtrlMsg.Ping(p[1].toInt())
                "PONG" -> CtrlMsg.Pong(p[1].toInt())
                "WHO" -> CtrlMsg.Who(
                    p[1],
                    p[2],
                    p[3].toInt(),
                    p.getOrElse(4) { "1" }.toInt(),
                    p.getOrElse(5) { p[2] },
                )
                "WHOR" -> CtrlMsg.WhoR(p[1], p[2], p[3] == "1", p[4].toInt())
                "SEE" -> CtrlMsg.See(
                    p[1],
                    unb64(p[2]),
                    unb64(p[3]),
                    p[4].toInt(),
                    p.getOrElse(5) { "1" }.toInt(),
                    p.getOrElse(6) { p[1] },
                )
                "CLIMB" -> CtrlMsg.Climb(unb64(p[1]), unb64(p[2]))
                "GOING" -> CtrlMsg.Going(
                    p[1],
                    p.getOrElse(2) { p[1] },
                    p.getOrElse(3) { "1" }.toInt(),
                    p.getOrElse(4) { p.getOrElse(2) { p[1] } },
                )
                "MERGE" -> CtrlMsg.Merge(p[1])
                else -> null
            }
        }.getOrNull()
    }

    private fun packRoutes(routes: List<Pair<String, Int>>): String =
        routes.joinToString(",") { "${it.first}/${it.second}" }

    private fun unpackRoutes(raw: String): List<Pair<String, Int>> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { item ->
            val parts = item.split('/')
            if (parts.size != 2) return@mapNotNull null
            val hops = parts[1].toIntOrNull() ?: return@mapNotNull null
            parts[0] to hops
        }
    }

    private fun b64(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun unb64(text: String): String =
        String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
}
