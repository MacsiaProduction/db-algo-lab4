package benchmarks

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
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

/**
 * Measures read throughput after filling the map to [entries] keys.
 * Large values (100M, 300M) need `-Xmx28g` and sufficient RAM; reduce via `-Pjmh.includes` if OOM.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
open class ScalingBenchmark {

    @Param("OWN", "JDK", "SYNC")
    lateinit var impl: String

    @Param(
        "1000",
        "10000",
        "100000",
        "1000000",
        "10000000",
        "100000000",
        "300000000",
    )
    lateinit var entriesStr: String

    private var entries: Int = 0
    private lateinit var map: IntLongMap

    @Setup(Level.Trial)
    fun setup() {
        entries = entriesStr.toInt()
        map = newMap(ImplKind.valueOf(impl))
        var i = 0
        while (i < entries) {
            map.putM(i, i.toLong())
            i++
        }
    }

    @Benchmark
    @Threads(8)
    fun scalingRead_thr08(bh: Blackhole) {
        val r = java.util.concurrent.ThreadLocalRandom.current()
        val k = r.nextKey(entries)
        bh.consume(map.getM(k))
    }
}
