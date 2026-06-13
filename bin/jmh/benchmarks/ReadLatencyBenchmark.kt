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
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/** Латентность чтения в sample-режиме (перцентили попадают в JSON для графиков). */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
open class ReadLatencyBenchmark {

    @Param("OWN", "JDK", "SYNC")
    lateinit var impl: String

    private lateinit var map: IntLongMap
    private val range = 1_000_000
    @Setup
    fun setup() {
        map = newMap(ImplKind.valueOf(impl))
        for (i in 0 until range) {
            map.putM(i, i.toLong())
        }
    }

    @Benchmark
    fun getSample(bh: Blackhole) {
        val k = ThreadLocalRandom.current().nextKey(range)
        bh.consume(map.getM(k))
    }
}
