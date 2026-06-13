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
open class ReadBenchmark {

    @Param("OWN", "JDK", "SYNC")
    lateinit var impl: String

    @Param("1048576")
    lateinit var rangeStr: String

    private var range: Int = 0
    private lateinit var map: IntLongMap

    @Setup
    fun setup() {
        range = rangeStr.toInt()
        val kind = ImplKind.valueOf(impl)
        map = newMap(kind)
        for (i in 0 until range) {
            map.putM(i, i.toLong())
        }
    }

    private fun readWork(bh: Blackhole) {
        val r = java.util.concurrent.ThreadLocalRandom.current()
        val k = r.nextKey(range)
        bh.consume(map.getM(k))
    }

    @Benchmark
    @Threads(1)
    fun read_thr01(bh: Blackhole) = readWork(bh)

    @Benchmark
    @Threads(2)
    fun read_thr02(bh: Blackhole) = readWork(bh)

    @Benchmark
    @Threads(4)
    fun read_thr04(bh: Blackhole) = readWork(bh)

    @Benchmark
    @Threads(8)
    fun read_thr08(bh: Blackhole) = readWork(bh)

    @Benchmark
    @Threads(16)
    fun read_thr16(bh: Blackhole) = readWork(bh)

    @Benchmark
    @Threads(32)
    fun read_thr32(bh: Blackhole) = readWork(bh)
}

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(1)
@State(Scope.Benchmark)
open class ReadBenchmarkUnsafe {

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
            own.putM(i, i.toLong())
            unsafe.putM(i, i.toLong())
        }
    }

    private fun readWork(map: IntLongMap, bh: Blackhole) {
        val r = java.util.concurrent.ThreadLocalRandom.current()
        val k = r.nextKey(range)
        bh.consume(map.getM(k))
    }

    @Benchmark
    @Threads(1)
    fun readUnsafe_thr01(bh: Blackhole) = readWork(unsafe, bh)

    @Benchmark
    @Threads(1)
    fun readOwnSingle_thr01(bh: Blackhole) = readWork(own, bh)
}
