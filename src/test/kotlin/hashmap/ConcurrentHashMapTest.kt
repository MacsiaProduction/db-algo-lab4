package hashmap

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConcurrentHashMapTest {

    @Test
    fun sequentialPutGetReplace() {
        val m = ConcurrentHashMap<String, Int>()
        assertNull(m.put("a", 1))
        assertEquals(1, m.get("a"))
        assertEquals(1, m.put("a", 2))
        assertEquals(2, m.get("a"))
        assertEquals(1L, m.size())
    }

    @Test
    fun clearAndSize() {
        val m = ConcurrentHashMap<String, Int>()
        m.put("x", 1)
        m.put("y", 2)
        assertEquals(2L, m.size())
        m.clear()
        assertEquals(0L, m.size())
        assertNull(m.get("x"))
    }

    @Test
    fun mergeInsertAndUpdate() {
        val m = ConcurrentHashMap<String, Int>()
        assertEquals(1, m.merge("k", 1) { a, b -> a + b })
        assertEquals(3, m.merge("k", 2) { a, b -> a + b })
        assertEquals(3, m.get("k"))
    }

    @Test
    fun iteratorCollectsEntries() {
        val m = ConcurrentHashMap<Int, String>()
        for (i in 0 until 100) m.put(i, "v$i")
        val pairs = m.iterator().asSequence().sortedBy { it.first }.toList()
        assertEquals(100, pairs.size)
        assertEquals(0 to "v0", pairs.first())
    }

    @Test
    fun concurrentDistinctPuts() {
        val m = ConcurrentHashMap<Int, Int>(segmentCount = 32, initialBucketsPerSegment = 8)
        val threads = 8
        val per = 1000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        for (t in 0 until threads) {
            pool.submit {
                start.await()
                val base = t * per
                for (i in 0 until per) {
                    m.put(base + i, i)
                }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertEquals((threads * per).toLong(), m.size())
        for (k in 0 until threads * per) {
            assertEquals(k % per, m.get(k))
        }
    }

    @Test
    fun concurrentMergeOnSameKey() {
        val m = ConcurrentHashMap<String, Long>()
        val n = 10_000
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) {
            pool.submit {
                start.await()
                repeat(n) {
                    m.merge("sum", 1L) { a, b -> a + b }
                }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertEquals((4L * n), m.get("sum"))
    }

    @Test
    fun resizeTriggersStillConsistent() {
        val m = ConcurrentHashMap<Int, Int>(segmentCount = 4, initialBucketsPerSegment = 2)
        for (i in 0 until 5000) {
            m.put(i, i)
        }
        val keys = (0 until 5000).map { requireNotNull(m.get(it)) { "missing $it" } }
        assertContentEquals((0 until 5000).toList(), keys)
        assertEquals(5000L, m.size())
    }

    @Test
    fun spreadUsesCompanion() {
        val h = ConcurrentHashMap.spread(0xdeadbeef.toInt())
        assertTrue(h >= 0)
    }

    @Test
    fun concurrentPutsAcrossSegmentGrowth() {
        val m = ConcurrentHashMap<Int, Int>(
            segmentCount = 1,
            initialBucketsPerSegment = 2,
            maxSegmentCount = 64,
            segmentGrowWatermark = 64,
        )
        val threads = 8
        val per = 20_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        for (t in 0 until threads) {
            pool.submit {
                start.await()
                val base = t * per
                for (i in 0 until per) {
                    m.put(base + i, i)
                }
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertEquals((threads * per).toLong(), m.size())
        assertTrue(m.segmentCount() > 1, "segments must have grown, got ${m.segmentCount()}")
        for (k in 0 until threads * per) {
            assertEquals(k % per, m.get(k))
        }
    }

    @Test
    fun growthPreservesEntriesUnderConcurrentReads() {
        val m = ConcurrentHashMap<Int, Int>(
            segmentCount = 2,
            initialBucketsPerSegment = 2,
            maxSegmentCount = 128,
            segmentGrowWatermark = 128,
        )
        val n = 100_000
        val readers = 4
        val stop = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(readers)
        repeat(readers) {
            pool.submit {
                while (stop.count > 0) {
                    val k = (Math.random() * n).toInt()
                    val v = m.get(k)
                    if (v != null) {
                        require(v == k) { "corrupted read: key=$k value=$v" }
                    }
                }
            }
        }
        for (i in 0 until n) {
            m.put(i, i)
        }
        stop.countDown()
        pool.shutdown()
        assertEquals(n.toLong(), m.size())
        assertTrue(m.segmentCount() > 2, "segments must have grown, got ${m.segmentCount()}")
        for (k in 0 until n) {
            assertEquals(k, m.get(k))
        }
    }
}
