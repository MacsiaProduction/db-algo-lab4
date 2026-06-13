package benchmarks

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(1)
@State(Scope.Benchmark)
open class WriteBenchmark {

    @Param("OWN", "JDK", "SYNC")
    lateinit var impl: String

    @Param("1048576")
    lateinit var rangeStr: String

    private var range: Int = 0
    private lateinit var map: IntLongMap

    @Setup
    fun setup() {
        range = rangeStr.toInt()
        map = newMap(ImplKind.valueOf(impl))
        for (i in 0 until range) {
            map.putM(i, 0L)
        }
    }

    private fun writeWork(bh: Blackhole) {
        val r = java.util.concurrent.ThreadLocalRandom.current()
        val k = r.nextKey(range)
        bh.consume(map.putM(k, r.nextLong()))
    }

    @Benchmark
    @Threads(1)
    fun write_thr01(bh: Blackhole) = writeWork(bh)

    @Benchmark
    @Threads(2)
    fun write_thr02(bh: Blackhole) = writeWork(bh)

    @Benchmark
    @Threads(4)
    fun write_thr04(bh: Blackhole) = writeWork(bh)

    @Benchmark
    @Threads(8)
    fun write_thr08(bh: Blackhole) = writeWork(bh)

    @Benchmark
    @Threads(16)
    fun write_thr16(bh: Blackhole) = writeWork(bh)

    @Benchmark
    @Threads(32)
    fun write_thr32(bh: Blackhole) = writeWork(bh)
}

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(1)
@State(Scope.Benchmark)
open class WriteBenchmarkUnsafe {

    @Param("1048576")
    lateinit var rangeStr: String

    private var range: Int = 0
    private lateinit var own: IntLongMap
    private lateinit var unsafe: IntLongMap

    @Setup
    fun setup() {
        range = rangeStr.toInt()
        own = newMap(ImplKind.OWN)
        unsafe = newMap(ImplKind.UNSAFE)
        for (i in 0 until range) {
            own.putM(i, 0L)
            unsafe.putM(i, 0L)
        }
    }

    private fun writeWork(map: IntLongMap, bh: Blackhole) {
        val r = java.util.concurrent.ThreadLocalRandom.current()
        val k = r.nextKey(range)
        bh.consume(map.putM(k, r.nextLong()))
    }

    @Benchmark
    @Threads(1)
    fun writeUnsafe_thr01(bh: Blackhole) = writeWork(unsafe, bh)

    @Benchmark
    @Threads(1)
    fun writeOwnSingle_thr01(bh: Blackhole) = writeWork(own, bh)
}
