package benchmarks

import hashmap.ConcurrentHashMap
import hashmap.PlainHashMap
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap as JdkCHM
import java.util.concurrent.ThreadLocalRandom

/** [UNSAFE] = однопоточный [PlainHashMap] (обёртка java.util.HashMap), не sun.misc.Unsafe. */
enum class ImplKind { OWN, JDK, SYNC, UNSAFE }

/** Минимальный интерфейс мапа для JMH-нагрузок (Int-ключи, Long-значения). */
interface IntLongMap {
    fun putM(k: Int, v: Long): Long?
    fun getM(k: Int): Long?
    fun mergeM(k: Int, v: Long, merger: (Long, Long) -> Long): Long
    fun clearM()
    fun sizeM(): Long
}

fun newMap(kind: ImplKind): IntLongMap =
    when (kind) {
        ImplKind.OWN ->
            object : IntLongMap {
                // LAB_OWN_MAX_SEGMENTS=16 отключает рост сегментов (baseline для A/B); без env — дефолтный рост.
                private val m =
                    System.getenv("LAB_OWN_MAX_SEGMENTS")?.toIntOrNull()
                        ?.let { ConcurrentHashMap<Int, Long>(maxSegmentCount = it) }
                        ?: ConcurrentHashMap<Int, Long>()
                override fun putM(k: Int, v: Long) = m.put(k, v)
                override fun getM(k: Int) = m.get(k)
                override fun mergeM(k: Int, v: Long, merger: (Long, Long) -> Long): Long =
                    m.merge(k, v, merger)

                override fun clearM() = m.clear()
                override fun sizeM(): Long = m.size()
            }
        ImplKind.JDK ->
            object : IntLongMap {
                private val m = JdkCHM<Int, Long>()
                override fun putM(k: Int, v: Long) = m.put(k, v)
                override fun getM(k: Int) = m[k]
                override fun mergeM(k: Int, v: Long, merger: (Long, Long) -> Long): Long =
                    requireNotNull(m.merge(k, v) { a, b -> merger(a, b) })

                override fun clearM() = m.clear()
                override fun sizeM(): Long = m.size.toLong()
            }
        ImplKind.SYNC ->
            object : IntLongMap {
                private val m = Collections.synchronizedMap(HashMap<Int, Long>())
                override fun putM(k: Int, v: Long) = m.put(k, v)
                override fun getM(k: Int) = m[k]
                override fun mergeM(k: Int, v: Long, merger: (Long, Long) -> Long): Long =
                    synchronized(m) {
                        val cur = m[k]
                        if (cur == null) {
                            m[k] = v
                            v
                        } else {
                            val nv = merger(cur, v)
                            m[k] = nv
                            nv
                        }
                    }

                override fun clearM() = m.clear()
                override fun sizeM(): Long = m.size.toLong()
            }
        ImplKind.UNSAFE ->
            object : IntLongMap {
                private val m = PlainHashMap<Int, Long>()
                override fun putM(k: Int, v: Long) = m.put(k, v)
                override fun getM(k: Int) = m.get(k)
                override fun mergeM(k: Int, v: Long, merger: (Long, Long) -> Long): Long =
                    m.merge(k, v, merger)

                override fun clearM() = m.clear()
                override fun sizeM(): Long = m.size()
            }
    }

fun ThreadLocalRandom.nextKey(range: Int): Int =
    if (range <= 1) 0 else nextInt(range)
