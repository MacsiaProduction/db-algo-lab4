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
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(1)
@State(Scope.Benchmark)
open class MixedBenchmark {

    @Param("OWN", "JDK", "SYNC")
    lateinit var impl: String

    /** Доля чтений в [0,1]. */
    @Param("0.8", "0.5", "0.2")
    lateinit var readShareStr: String

    @Param("1048576")
    lateinit var rangeStr: String

    private var range: Int = 0
    private var readShare: Double = 0.0
    private lateinit var map: IntLongMap

    @Setup
    fun setup() {
        range = rangeStr.toInt()
        readShare = readShareStr.toDouble()
        map = newMap(ImplKind.valueOf(impl))
        for (i in 0 until range) {
            map.putM(i, i.toLong())
        }
    }

    private fun mixedWork(bh: Blackhole) {
        val r = ThreadLocalRandom.current()
        val k = r.nextKey(range)
        if (r.nextDouble() < readShare) {
            bh.consume(map.getM(k))
        } else {
            bh.consume(map.putM(k, r.nextLong()))
        }
    }

    @Benchmark
    @Threads(1)
    fun mixed_thr01(bh: Blackhole) = mixedWork(bh)

    @Benchmark
    @Threads(2)
    fun mixed_thr02(bh: Blackhole) = mixedWork(bh)

    @Benchmark
    @Threads(4)
    fun mixed_thr04(bh: Blackhole) = mixedWork(bh)

    @Benchmark
    @Threads(8)
    fun mixed_thr08(bh: Blackhole) = mixedWork(bh)

    @Benchmark
    @Threads(16)
    fun mixed_thr16(bh: Blackhole) = mixedWork(bh)

    @Benchmark
    @Threads(32)
    fun mixed_thr32(bh: Blackhole) = mixedWork(bh)
}
