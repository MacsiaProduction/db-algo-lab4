# Lab 4 — Thread-safe closed-addressing hash table (Kotlin)

Implements [`hashmap.ConcurrentHashMap`](src/main/kotlin/hashmap/ConcurrentHashMap.kt): striped segments (`ReentrantLock`), separate chaining, **lock-free reads** on hot paths (`AtomicReferenceArray` bucket heads + `@Volatile` `Node.value` / `Node.next`), per-segment resize, `merge`, `clear`, weakly consistent `iterator()`, and `size()` via per-segment `AtomicLong` counters (plan mentioned `LongAdder`; `AtomicLong` keeps `clear()` simple on older JDKs).

Baseline: [`hashmap.UnsafeHashMap`](src/main/kotlin/hashmap/UnsafeHashMap.kt) (thin `HashMap` wrapper, **single-thread only**).

## Build / test

```bash
./gradlew test
./gradlew jcstress   # concurrency stress (tough mode; can be slow)
```

Uses **Gradle 9.5+** (wrapper) so the build runs on **JDK 22+** (tested on **JDK 26**). Bytecode targets **Java 21** (`release` / Kotlin `jvmTarget`).

## JMH benchmarks

```bash
mkdir -p results/graphs
./gradlew jmh
```

- Results JSON: `results/jmh-results.json` (Gradle `jmh` block).
- Heap for JMH forks: set **`-Pjmh.heap=28g`** for large `ScalingBenchmark`; default in `build.gradle.kts` is **8g** if unset.

**Quick smoke** (subset, short iterations, 1s time where supported):

```bash
./gradlew jmh --no-daemon -Pjmh.light=true -Pjmh.heap=1536m
```

**Filter benchmarks** (e.g. skip huge fills): prefer the **`jmh.light`** profile above, or adjust `includes` / benchmark `@Param` lists in source. The `jmh` Gradle task may not accept `--args` on all plugin versions.

**Scaling / memory:** `ScalingBenchmark` includes `300000000` entries — that can require **tens of GB** of heap for boxed keys/values. If you OOM or thrash, reduce params in source or filter:

Narrow `ScalingBenchmark` entry counts in [`ScalingBenchmark.kt`](src/jmh/kotlin/benchmarks/ScalingBenchmark.kt) (`@Param` list) or run a filtered profile; see [JMH](https://github.com/openjdk/jmh) for CLI options if your Gradle/JMH plugin exposes them.

## Graphs

After `jmh`:

```bash
python3 -m pip install -r scripts/requirements.txt
python3 scripts/plot_results.py
# or
./scripts/run_benchmarks.sh
```

Plots under `results/graphs/`:

1. **Throughput vs threads** for read/write (+ unsafe) families (log-scaled Y). **Mixed** is split into **`throughput_threads_MixedBenchmark_rs0.2|0.5|0.8.png`** (one line per `readShareStr`). **Scaling** is only in **`scaling_loglog.png`** (not the generic throughput-by-thread chart).
2. **Speedup vs `SYNC`** (OWN/JDK ÷ SYNC), one panel per family; mixed lines include **`rs=`** in the legend; **log Y** for ratios.
3. **Scaling** log–log: entries vs read throughput (`ScalingBenchmark`) with error bars when `scoreError` is finite.
4. **Latency percentiles** from `ReadLatencyBenchmark` (SampleTime JSON).
5. **Mixed workload heatmaps** (`readShareStr` × threads) per implementation.

Custom JSON path: `JMH_RESULTS_JSON=/path/to.json python3 scripts/plot_results.py`.

## Flame graphs (async-profiler)

The JMH Gradle integration for profilers varies by version/OS. Practical approach:

1. Install [async-profiler](https://github.com/jvm-profiling-tools/async-profiler).
2. `export ASYNC_PROFILER_HOME=/path/to/async-profiler`
3. Run `./scripts/run_benchmarks.sh` — when `ASYNC_PROFILER_HOME` is set, it wraps a short `jmh` invocation and writes `results/flamegraph/cpu.html`.

Alternatively attach `profiler.sh` manually to a long-running JVM.

## Interpreting results (report hints)

- **OWN vs JDK `ConcurrentHashMap`:** JDK uses a highly tuned tree/bin structure; striped chaining often trails on writes but can be competitive on **read-heavy** workloads with large fan-out.
- **`SYNC` baseline:** coarse lock ⇒ throughput collapses as threads grow; speedup plot vs SYNC should rise for concurrent maps — if not, check pinning / insufficient CPU / biased locking effects.
- **Scaling log–log:** expect a **knee** when the working set leaves last-level cache; very large maps add GC and pointer-chasing cost — if curves diverge only for OWN, profile allocation/rehash paths (flame graph).
- **Mixed heatmaps:** higher read share favors lock-free reads; lower read share stresses write paths and segment locks.

## jcstress

Tests live under [`src/jcstress/java/stress/`](src/jcstress/java/stress/):

- `PutGetStressTest` — put + concurrent get; arbiter observes `0` or `1`.
- `ConcurrentPutStressTest` — two `put`s; final value `10` or `20`.
- `MergeAtomicityStressTest` — two `merge(+1)`; expect `2` (forbidden: lost update).

## Project layout

| Path | Role |
|------|------|
| `src/main/kotlin/hashmap/` | `ConcurrentHashMap`, `UnsafeHashMap` |
| `src/test/kotlin/hashmap/` | JUnit tests |
| `src/jmh/kotlin/benchmarks/` | JMH |
| `src/jcstress/java/stress/` | jcstress |
| `scripts/plot_results.py` | Matplotlib plots |
| `scripts/run_benchmarks.sh` | JMH + plots + optional jcstress + async-profiler |
