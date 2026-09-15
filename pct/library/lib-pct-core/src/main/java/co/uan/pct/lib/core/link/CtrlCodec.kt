package co.uan.pct.lib.core.link

/** Mensajes del TCP de control `:8765`. Una línea de texto por mensaje. */
sealed interface CtrlMsg {
    /** Quién soy: nid, profundidad, raíz de mi árbol y las rutas que conozco. */
    data class Hi(
        val nid: String,
        val depth: Int,
        val tree: String,
        val routes: List<Pair<String, Int>>,
    ) : CtrlMsg

    /** Resumen de rutas `destino → saltos` para que el vecino fusione. */
    data class Tab(val routes: List<Pair<String, Int>>) : CtrlMsg

    data class Ping(val seq: Int) : CtrlMsg
    data class Pong(val seq: Int) : CtrlMsg
}

object CtrlCodec {
    fun encode(msg: CtrlMsg): String = when (msg) {
        is CtrlMsg.Hi ->
            "HI ${msg.nid} ${msg.depth} ${msg.tree} ${packRoutes(msg.routes)}"
        is CtrlMsg.Tab ->
            "TAB ${packRoutes(msg.routes)}"
        is CtrlMsg.Ping -> "PING ${msg.seq}"
        is CtrlMsg.Pong -> "PONG ${msg.seq}"
    }

    /** null si la línea no es un mensaje conocido; el lector la ignora y sigue. */
    fun decode(line: String): CtrlMsg? {
        val p = line.trim().split(' ')
        if (p.isEmpty() || p[0].isEmpty()) return null
        return runCatching {
            when (p[0]) {
                "HI" -> CtrlMsg.Hi(p[1], p[2].toInt(), p[3], unpackRoutes(p.getOrElse(4) { "" }))
                "TAB" -> CtrlMsg.Tab(unpackRoutes(p.getOrElse(1) { "" }))
                "PING" -> CtrlMsg.Ping(p[1].toInt())
                "PONG" -> CtrlMsg.Pong(p[1].toInt())
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
}
