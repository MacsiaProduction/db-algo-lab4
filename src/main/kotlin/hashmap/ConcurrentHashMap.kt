package hashmap

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.ReentrantLock

/**
 * Thread-safe closed-addressing hash table: per-segment [ReentrantLock], separate chaining.
 * Reads ([get]) traverse bucket heads and [Node.next] without locks; visibility via [@Volatile].
 */
class ConcurrentHashMap<K : Any, V : Any>(
    segmentCount: Int = 16,
    initialBucketsPerSegment: Int = 16,
) {
    private val segmentMask: Int
    /** log2(segmentCount); used to shift hash for bucket indexing */
    private val segmentBitCount: Int
    private val segments: Array<Segment<K, V>>

    init {
        require(segmentCount > 0 && segmentCount and segmentCount - 1 == 0) {
            "segmentCount must be a power of two, got $segmentCount"
        }
        require(initialBucketsPerSegment > 0 && initialBucketsPerSegment and initialBucketsPerSegment - 1 == 0) {
            "initialBucketsPerSegment must be a power of two, got $initialBucketsPerSegment"
        }
        segmentMask = segmentCount - 1
        segmentBitCount = Integer.numberOfTrailingZeros(segmentCount)
        segments = Array(segmentCount) { Segment(initialBucketsPerSegment, segmentBitCount) }
    }

    fun put(key: K, value: V): V? {
        val h = spread(key.hashCode())
        val seg = segmentFor(h)
        seg.lock.lock()
        try {
            return seg.putLocked(h, key, value)
        } finally {
            seg.lock.unlock()
        }
    }

    fun get(key: K): V? {
        val h = spread(key.hashCode())
        val seg = segmentFor(h)
        var n = seg.getHeadVolatile(h)
        while (n != null) {
            if (n.key == key) {
                return n.value
            }
            n = n.next
        }
        return null
    }

    fun size(): Long {
        var sum = 0L
        for (s in segments) {
            sum += s.entryCount.get()
        }
        return sum
    }

    fun clear() {
        for (s in segments) {
            s.lock.lock()
        }
        try {
            for (s in segments) {
                s.clearLocked()
            }
        } finally {
            for (i in segments.indices.reversed()) {
                segments[i].lock.unlock()
            }
        }
    }

    /**
     * Atomically updates the value for [key] using [merger]; returns the new value.
     * If the key was absent, it is set to [value] and [value] is returned.
     */
    fun merge(key: K, value: V, merger: (V, V) -> V): V {
        val h = spread(key.hashCode())
        val seg = segmentFor(h)
        seg.lock.lock()
        try {
            return seg.mergeLocked(h, key, value, merger)
        } finally {
            seg.lock.unlock()
        }
    }

    /**
     * Weakly consistent iterator: pairs observed while each segment was locked once
     * (heads + chains at that time); does not reflect concurrent updates.
     */
    fun iterator(): Iterator<Pair<K, V>> {
        val out = ArrayList<Pair<K, V>>()
        for (seg in segments) {
            seg.lock.lock()
            try {
                val tab = seg.bucketsView()
                for (i in 0 until tab.length()) {
                    var n = tab.get(i)
                    while (n != null) {
                        out.add(n.key to n.value)
                        n = n.next
                    }
                }
            } finally {
                seg.lock.unlock()
            }
        }
        return out.iterator()
    }

    private fun segmentFor(spreadHash: Int): Segment<K, V> =
        segments[spreadHash and segmentMask]

    internal class Node<K : Any, V : Any>(
        @JvmField val key: K,
        @Volatile @JvmField var value: V,
        @Volatile @JvmField var next: Node<K, V>?,
    )

    internal class Segment<K : Any, V : Any>(
        initialCap: Int,
        private val segmentBitCount: Int,
    ) {
        val lock = ReentrantLock()
        val entryCount = AtomicLong(0)
        /** Volatile so lock-free [get] sees array swaps after [resizeLocked]. */
        @Volatile private var buckets: AtomicReferenceArray<Node<K, V>?> = AtomicReferenceArray(initialCap)
        private var threshold: Int = (initialCap * LOAD_FACTOR).toInt()

        fun bucketsView(): AtomicReferenceArray<Node<K, V>?> = buckets

        private fun mask(): Int = buckets.length() - 1

        private fun indexFor(spreadHash: Int): Int =
            (spreadHash ushr segmentBitCount) and mask()

        fun getHeadVolatile(spreadHash: Int): Node<K, V>? =
            buckets.get(indexFor(spreadHash))

        fun putLocked(spreadHash: Int, key: K, value: V): V? {
            val tab = buckets
            val idx = indexFor(spreadHash)
            var head = tab.get(idx)
            var p: Node<K, V>? = head
            while (p != null) {
                if (p.key == key) {
                    val old = p.value
                    p.value = value
                    return old
                }
                p = p.next
            }
            val newNode = Node(key, value, head)
            tab.set(idx, newNode)
            val c = entryCount.incrementAndGet()
            if (c > threshold) {
                resizeLocked()
            }
            return null
        }

        fun mergeLocked(
            spreadHash: Int,
            key: K,
            value: V,
            merger: (V, V) -> V,
        ): V {
            val tab = buckets
            val idx = indexFor(spreadHash)
            var head = tab.get(idx)
            var p: Node<K, V>? = head
            while (p != null) {
                if (p.key == key) {
                    val nv = merger(p.value, value)
                    p.value = nv
                    return nv
                }
                p = p.next
            }
            val newNode = Node(key, value, head)
            tab.set(idx, newNode)
            val c = entryCount.incrementAndGet()
            if (c > threshold) {
                resizeLocked()
            }
            return value
        }

        private fun resizeLocked() {
            val old = buckets
            val oldLen = old.length()
            val newLen = oldLen shl 1
            if (newLen <= 0) return
            val newTab = AtomicReferenceArray<Node<K, V>?>(newLen)
            for (i in 0 until oldLen) {
                var e = old.get(i)
                while (e != null) {
                    val next = e.next
                    val sh = ConcurrentHashMap.spread(e.key.hashCode())
                    val newIdx = (sh ushr segmentBitCount) and (newLen - 1)
                    e.next = newTab.get(newIdx)
                    newTab.set(newIdx, e)
                    e = next
                }
            }
            buckets = newTab
            threshold = (newLen * LOAD_FACTOR).toInt()
        }

        fun clearLocked() {
            for (i in 0 until buckets.length()) {
                buckets.set(i, null)
            }
            entryCount.set(0)
        }
    }

    companion object {
        private const val LOAD_FACTOR = 0.75f

        @JvmStatic
        internal fun spread(h: Int): Int = (h xor (h ushr 16)) and 0x7fffffff
    }
}
