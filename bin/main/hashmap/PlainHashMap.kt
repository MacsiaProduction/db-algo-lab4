package hashmap

/** Однопоточный baseline: обёртка над [java.util.HashMap] с API как у [ConcurrentHashMap]. Не связан с sun.misc.Unsafe. */
class PlainHashMap<K : Any, V : Any>(initialCapacity: Int = 16) {
    private val map = HashMap<K, V>(initialCapacity)

    fun put(key: K, value: V): V? = map.put(key, value)

    fun get(key: K): V? = map[key]

    fun size(): Long = map.size.toLong()

    fun clear() = map.clear()

    fun merge(key: K, value: V, merger: (V, V) -> V): V {
        val cur = map[key]
        return if (cur == null) {
            map[key] = value
            value
        } else {
            val nv = merger(cur, value)
            map[key] = nv
            nv
        }
    }

    fun iterator(): Iterator<Pair<K, V>> =
        map.entries.asSequence().map { it.key to it.value }.iterator()
}
