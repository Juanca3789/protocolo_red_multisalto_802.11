package co.uan.pct.lib.core.data

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Dedup de broadcast por `(origin_uuid, msg_id)`; LRU acotado. */
@OptIn(ExperimentalUuidApi::class)
class SeenCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lock = Any()
    private val seen = object : LinkedHashMap<Pair<Uuid, Long>, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Uuid, Long>, Unit>?): Boolean =
            size > capacity
    }

    /** `true` si es la primera vez que se ve; `false` si ya estaba (duplicado). */
    fun markIfNew(origin: Uuid, msgId: Long): Boolean = synchronized(lock) {
        seen.put(origin to msgId, Unit) == null
    }

    fun clear() = synchronized(lock) { seen.clear() }

    private companion object {
        const val DEFAULT_CAPACITY = 512
    }
}
