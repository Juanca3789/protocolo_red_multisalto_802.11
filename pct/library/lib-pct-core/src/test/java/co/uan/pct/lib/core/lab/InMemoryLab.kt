package co.uan.pct.lib.core.lab

import co.uan.pct.lib.core.link.EventWalks
import co.uan.pct.lib.core.link.RouteTable
import co.uan.pct.lib.core.link.WalkArrival
import co.uan.pct.lib.core.link.WalkKind
import co.uan.pct.lib.core.link.eventId
import co.uan.pct.lib.core.net.HopTable
import co.uan.pct.lib.core.net.MeshSocket
import co.uan.pct.lib.core.net.UserCodec

internal class InMemoryLab {
    private val nodes = linkedMapOf<String, MemNode>()

    fun node(id: String): MemNode = nodes.getOrPut(id) { MemNode(id, this) }

    fun get(id: String): MemNode? = nodes[id]

    fun all(): Collection<MemNode> = nodes.values

    suspend fun deliver(from: String, to: String, bytes: ByteArray): Boolean {
        val dest = nodes[to] ?: return false
        val frame = UserCodec.decode(bytes) ?: return false
        return dest.mesh.onHop(from, frame)
    }

    fun gossipTabs(rounds: Int = 8) {
        repeat(rounds) {
            for (node in nodes.values) {
                for (nbId in node.table.neighbors()) {
                    nodes[nbId]?.table?.mergeFrom(node.id, node.table.advertise())
                }
            }
        }
    }
}

internal class MemNode(
    val id: String,
    private val lab: InMemoryLab,
) {
    val table = RouteTable(id)
    val walks = EventWalks()
    val mesh = MeshSocket(
        id,
        object : HopTable {
            override suspend fun nextNid(dest: String): String? =
                table.find(dest)?.next?.takeUnless { UserCodec.sameNid(it, id) }

            override suspend fun writeNext(nextNid: String, bytes: ByteArray): Boolean =
                lab.deliver(id, nextNid, bytes)
        },
    )

    fun hear(other: MemNode, asParent: Boolean) {
        table.installNeighbor(other.id, "10.0.0.${other.id.first().code % 250}", asParent)
    }

    fun exchangeTabs(other: MemNode) {
        table.mergeFrom(other.id, other.table.advertise())
        other.table.mergeFrom(id, table.advertise())
    }
}

internal fun chain(parent: MemNode, child: MemNode) {
    parent.hear(child, asParent = false)
    child.hear(parent, asParent = true)
    parent.exchangeTabs(child)
}

internal fun whoSearch(origin: MemNode, target: String, graph: Map<String, MemNode>): Boolean {
    val books = graph.mapValues { EventWalks() }
    fun visit(id: String, from: String?, hops: Int, originId: String): Boolean {
        val node = graph[id] ?: return false
        if (node.table.known(target)) return true
        val book = books.getValue(id)
        if (from == null) {
            book.begin(
                WalkKind.Who,
                target,
                originId,
                hops,
                node.table.neighbors(),
                node.table.parentId,
                node.table::hopOf,
            )
        } else {
            when (
                book.arrive(
                    WalkKind.Who,
                    target,
                    originId,
                    from,
                    hops,
                    node.table.neighbors(),
                    node.table.parentId,
                    node.table::hopOf,
                )
            ) {
                WalkArrival.Loop, is WalkArrival.Keep -> return false
                else -> Unit
            }
        }
        val eid = eventId(WalkKind.Who, target)
        while (true) {
            val next = book.takeNext(eid) ?: break
            val known = visit(next, id, hops + 1, originId)
            book.onBranchDone(eid, next, known)
            if (known) return true
        }
        return book.get(eid)?.known == true
    }
    return visit(origin.id, null, 0, origin.id)
}

internal object LabIds {
    const val A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    const val C = "cccccccccccccccccccccccccccccccc"
    const val D = "dddddddddddddddddddddddddddddddd"
    const val E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
}
