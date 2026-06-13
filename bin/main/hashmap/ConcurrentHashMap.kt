package hashmap

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.ReentrantLock

/** Потокобезопасная хеш-таблица: лок на сегмент, separate chaining, чтения без локов (видимость через @Volatile). */
class ConcurrentHashMap<K : Any, V : Any> @JvmOverloads constructor(
    segmentCount: Int = 16,
    initialBucketsPerSegment: Int = 16,
    maxSegmentCount: Int = DEFAULT_MAX_SEGMENTS,
    private val segmentGrowWatermark: Int = DEFAULT_GROW_WATERMARK,
) {
    private val initialBucketsPerSegment: Int
    private val maxSegmentCount: Int

    /** Volatile-снимок раскладки сегментов: глобальный resize публикует новый [Table] одной записью. */
    @Volatile private var table: Table<K, V>

    /** Сериализует глобальный resize числа сегментов; на горячем пути не берётся. */
    private val growLock = ReentrantLock()

    init {
        require(segmentCount > 0 && segmentCount and segmentCount - 1 == 0) {
            "segmentCount must be a power of two, got $segmentCount"
        }
        require(initialBucketsPerSegment > 0 && initialBucketsPerSegment and initialBucketsPerSegment - 1 == 0) {
            "initialBucketsPerSegment must be a power of two, got $initialBucketsPerSegment"
        }
        require(maxSegmentCount >= segmentCount && maxSegmentCount and maxSegmentCount - 1 == 0) {
            "maxSegmentCount must be a power of two >= segmentCount, got $maxSegmentCount"
        }
        require(segmentGrowWatermark > 0) { "segmentGrowWatermark must be positive, got $segmentGrowWatermark" }
        this.initialBucketsPerSegment = initialBucketsPerSegment
        this.maxSegmentCount = maxSegmentCount
        table = Table(segmentCount, initialBucketsPerSegment)
    }

    fun put(key: K, value: V): V? {
        val h = spread(key.hashCode())
        while (true) {
            val t = table
            val seg = t.segmentFor(h)
            seg.lock.lock()
            var old: V? = null
            var committed = false
            try {
                // Раскладка могла смениться между чтением table и захватом лока — тогда повторяем на новой.
                if (table === t) {
                    old = seg.putLocked(h, key, value)
                    committed = true
                }
            } finally {
                seg.lock.unlock()
            }
            if (committed) {
                if (old == null && seg.entryCount.get() > segmentGrowWatermark) {
                    maybeGrowSegments(t)
                }
                return old
            }
        }
    }

    fun get(key: K): V? {
        val h = spread(key.hashCode())
        var n = table.segmentFor(h).getHeadVolatile(h)
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
        for (s in table.segments) {
            sum += s.entryCount.get()
        }
        return sum
    }

    /** Текущее число сегментов (для тестов и диагностики роста). */
    fun segmentCount(): Int = table.segments.size

    fun clear() {
        // growLock не даёт сменить раскладку, пока чистим; затем берём все локи текущей таблицы.
        growLock.lock()
        try {
            val segs = table.segments
            for (s in segs) {
                s.lock.lock()
            }
            try {
                for (s in segs) {
                    s.clearLocked()
                }
            } finally {
                for (i in segs.indices.reversed()) {
                    segs[i].lock.unlock()
                }
            }
        } finally {
            growLock.unlock()
        }
    }

    /** Атомарно объединяет значение по [key] через [merger]; для отсутствующего ключа кладёт [value]. */
    fun merge(key: K, value: V, merger: (V, V) -> V): V {
        val h = spread(key.hashCode())
        while (true) {
            val t = table
            val seg = t.segmentFor(h)
            seg.lock.lock()
            var result: V = value
            var committed = false
            try {
                if (table === t) {
                    result = seg.mergeLocked(h, key, value, merger)
                    committed = true
                }
            } finally {
                seg.lock.unlock()
            }
            if (committed) {
                if (seg.entryCount.get() > segmentGrowWatermark) {
                    maybeGrowSegments(t)
                }
                return result
            }
        }
    }

    /** Слабо-согласованный итератор: снимок пар на момент захвата лока каждого сегмента. */
    fun iterator(): Iterator<Pair<K, V>> {
        val out = ArrayList<Pair<K, V>>()
        val segs = table.segments
        for (seg in segs) {
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

    /** Удваивает число сегментов, если один из них перерос watermark; растёт только один поток за раз. */
    private fun maybeGrowSegments(seen: Table<K, V>) {
        if (seen !== table || seen.segments.size >= maxSegmentCount) {
            return
        }
        if (!growLock.tryLock()) {
            return
        }
        try {
            val cur = table
            if (cur !== seen || cur.segments.size >= maxSegmentCount || !cur.anySegmentExceeds(segmentGrowWatermark)) {
                return
            }
            growSegmentsLocked(cur)
        } finally {
            growLock.unlock()
        }
    }

    private fun growSegmentsLocked(cur: Table<K, V>) {
        val segs = cur.segments
        val newCount = segs.size shl 1
        if (newCount <= 0 || newCount > maxSegmentCount) {
            return
        }
        for (s in segs) {
            s.lock.lock()
        }
        try {
            var total = 0L
            for (s in segs) {
                total += s.entryCount.get()
            }
            // Новые сегменты ещё не опубликованы — никто их не видит, поэтому копируем без их локов.
            val next = Table<K, V>(newCount, bucketsForEntries(total / newCount))
            for (s in segs) {
                val tab = s.bucketsView()
                for (i in 0 until tab.length()) {
                    var n = tab.get(i)
                    while (n != null) {
                        val h = spread(n.key.hashCode())
                        next.segmentFor(h).putFreshLocked(h, n.key, n.value)
                        n = n.next
                    }
                }
            }
            table = next
        } finally {
            for (i in segs.indices.reversed()) {
                segs[i].lock.unlock()
            }
        }
    }

    private fun bucketsForEntries(entriesPerSegment: Long): Int {
        var cap = initialBucketsPerSegment
        val target = (entriesPerSegment / LOAD_FACTOR).toLong() + 1
        while (cap < target && cap < MAX_BUCKETS_PER_SEGMENT) {
            cap = cap shl 1
        }
        return cap
    }

    /** Неизменяемая раскладка сегментов; меняется только заменой целиком в [growSegmentsLocked]. */
    private class Table<K : Any, V : Any>(segmentCount: Int, bucketsPerSegment: Int) {
        private val segmentMask: Int = segmentCount - 1
        val segments: Array<Segment<K, V>>

        init {
            val segmentBitCount = Integer.numberOfTrailingZeros(segmentCount)
            segments = Array(segmentCount) { Segment(bucketsPerSegment, segmentBitCount) }
        }

        fun segmentFor(spreadHash: Int): Segment<K, V> = segments[spreadHash and segmentMask]

        fun anySegmentExceeds(watermark: Int): Boolean {
            for (s in segments) {
                if (s.entryCount.get() > watermark) {
                    return true
                }
            }
            return false
        }
    }

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
        /** Volatile: lock-free [get] видит замену массива после [resizeLocked]. */
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

        /** Вставка заведомо нового ключа (без поиска по цепочке) — для перераздачи при росте числа сегментов. */
        fun putFreshLocked(spreadHash: Int, key: K, value: V) {
            val tab = buckets
            val idx = indexFor(spreadHash)
            val newNode = Node(key, value, tab.get(idx))
            tab.set(idx, newNode)
            val c = entryCount.incrementAndGet()
            if (c > threshold) {
                resizeLocked()
            }
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
        private const val DEFAULT_MAX_SEGMENTS = 1 shl 10
        private const val DEFAULT_GROW_WATERMARK = 1 shl 14
        private const val MAX_BUCKETS_PER_SEGMENT = 1 shl 26

        @JvmStatic
        internal fun spread(h: Int): Int = (h xor (h ushr 16)) and 0x7fffffff
    }
}
