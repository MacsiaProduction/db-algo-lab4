# Lab 4 — Thread-safe closed-addressing hash table (Kotlin)

Implements [`hashmap.ConcurrentHashMap`](src/main/kotlin/hashmap/ConcurrentHashMap.kt): striped segments (`ReentrantLock`), separate chaining, **lock-free reads** on hot paths (`AtomicReferenceArray` bucket heads + `@Volatile` `Node.value` / `Node.next`), per-segment resize, `merge`, `clear`, weakly consistent `iterator()`, and `size()` via per-segment `AtomicLong` counters (plan mentioned `LongAdder`; `AtomicLong` keeps `clear()` simple on older JDKs).

Baseline: [`hashmap.PlainHashMap`](src/main/kotlin/hashmap/PlainHashMap.kt) — thin **`java.util.HashMap`** wrapper, **single-thread only** (not `sun.misc.Unsafe`; JMH enum label remains `UNSAFE`).

## Build / test

```bash
./gradlew test
./gradlew jcstress   # concurrency stress (quick mode; see build.gradle.kts)
```

Uses **Gradle 9.5+** (wrapper) so the build runs on **JDK 22+** (tested on **JDK 26**). Bytecode targets **Java 21** (`release` / Kotlin `jvmTarget`).

## JMH benchmarks

```bash
mkdir -p results/full/graphs
./gradlew jmh -Pjmh.resultsFile=results/full/jmh-results.json
```

- Results JSON: `results/full/jmh-results.json` (full run; flame runs use `results/flame/`).
- Heap for JMH forks: set **`-Pjmh.heap=28g`** for large `ScalingBenchmark`; default in `build.gradle.kts` is **8g** if unset.

**Quick smoke** (subset, short iterations, 1s time where supported):

```bash
./gradlew jmh --no-daemon -Pjmh.light=true -Pjmh.heap=1536m
```

**Filter benchmarks** (e.g. skip huge fills): prefer the **`jmh.light`** profile above, or adjust `includes` / benchmark `@Param` lists in source. The `jmh` Gradle task may not accept `--args` on all plugin versions.

**Scaling / memory:** `ScalingBenchmark` includes `300000000` entries — that can require **tens of GB** of heap for boxed keys/values. If you OOM or thrash, reduce params in source or filter:

Narrow `ScalingBenchmark` entry counts in [`ScalingBenchmark.kt`](src/jmh/kotlin/benchmarks/ScalingBenchmark.kt) (`@Param` list) or run a filtered profile; see [JMH](https://github.com/openjdk/jmh) for CLI options if your Gradle/JMH plugin exposes them.

## Overnight: two phases (benchmarks, then flames)

**Phase 1 — benchmarks only** (no profiler): `./meta_run_all.sh`
Lab4 `results/full/` · Lab3 `results/full/` + `docs/img/full/`.

**Phase 2 — flame-only** (after phase 1): `yay -S --needed async-profiler-bin` then `./meta_run_flame.sh`
Lab4 `results/flame/` · Lab3 `docs/img/full/hnsw_build_flame.svg`.

**Finish Lab3 notebook 05** (keeps 02–04 CSVs; refreshes comparison plots + `scaling.csv`):

```bash
cd ../db-algo-lab3
.venv/bin/python _build_notebooks.py   # if generator changed
LAB_N_SWEEP=1281167 LAB_SCALING_FULL=1 ./scripts/finish_and_flame.sh
```

Or from Lab4: `LAB_N_SWEEP=1281167 LAB_SCALING_FULL=1 ./scripts/finish_overnight.sh`

Skip long Lab3: `RUN_LAB3_FULL=0 ./meta_run_all.sh`. Lab4 only: `SKIP_LAB3=1 ./meta_run_all.sh`.

## Graphs

After `jmh`:

```bash
python3 -m pip install -r scripts/requirements.txt
python3 scripts/plot_results.py
# or
./scripts/run_benchmarks.sh
```

Plots under `results/full/graphs/`:

1. **Throughput vs threads** for read/write (+ unsafe) families (log-scaled Y). **Mixed** is split into **`throughput_threads_MixedBenchmark_rs0.2|0.5|0.8.png`** (one line per `readShareStr`). **Scaling** is only in **`scaling_loglog.png`** (not the generic throughput-by-thread chart).
2. **Speedup vs `SYNC`** (OWN/JDK ÷ SYNC), one panel per family; mixed lines include **`rs=`** in the legend; **log Y** for ratios.
3. **Scaling** log–log: entries vs read throughput (`ScalingBenchmark`) with error bars when `scoreError` is finite.
4. **Latency percentiles** from `ReadLatencyBenchmark` (SampleTime JSON).
5. **Mixed workload heatmaps** (`readShareStr` × threads) per implementation.

Custom JSON path: `JMH_RESULTS_JSON=/path/to.json python3 scripts/plot_results.py`.

## Flame graphs (async-profiler 4.x)

On Arch/CachyOS (prebuilt, recommended):

```bash
yay -S --needed async-profiler-bin
# optional: sudo sysctl kernel.perf_event_paranoid=1 kernel.kptr_restrict=0
./meta_run_flame.sh
```

Profiles only `WriteBenchmark.write_thr16` (**OWN** + **JDK**) → `results/flame/write_thr16_{OWN,JDK}.html`.

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
- `ResizeStressTest` — concurrent `put`/`get` across `resizeLocked`; `get` never sees a corrupted value.

## Project layout

| Path | Role |
|------|------|
| `src/main/kotlin/hashmap/` | `ConcurrentHashMap`, `PlainHashMap` |
| `src/test/kotlin/hashmap/` | JUnit tests |
| `src/jmh/kotlin/benchmarks/` | JMH |
| `src/jcstress/java/stress/` | jcstress |
| `scripts/plot_results.py` | Matplotlib plots |
| `meta_run_all.sh` | Overnight benchmarks (Lab4 + Lab3, no flame) |
| `meta_run_flame.sh` | Overnight flames only (Lab4 + Lab3) |
| `scripts/run_benchmarks.sh` | JMH → `results/full/` + plots + jcstress |
| `scripts/record_flame.sh` | Flame-only JMH → `results/flame/` |
