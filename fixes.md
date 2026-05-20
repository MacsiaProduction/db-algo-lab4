review done. Lab is solid overall but report has notable coverage gaps and a few methodology/script bugs.

**Resolution (2026-05):** Items 1–8 addressed in `BENCHMARK_REPORT.md`, `scripts/plot_results.py`, `PlainHashMap` rename, `ReadLatencyBenchmark` random-key (JSON in `results/full/` reflects this). Item 9 (flame): run `./meta_run_flame.sh` + Lab3 `scripts/record_flame.sh` → `docs/img/full/hnsw_build_flame.svg`.

## **Numbers in report match JSON ✓**

Tables in `BENCHMARK_REPORT.md` aligned with **`results/full/jmh-results.json`** (118 rows, May 2026 run). Legacy `results/jmh-results.json` may differ — use `results/full/` as canonical.

## **Unexplained results in** `BENCHMARK_REPORT.md`

These show up clearly in JSON/charts but report does **not** discuss them:


| **#** | **Observation (JSON)**                                                                                                                                                   | **Severity** | **What's missing**                                                                                                                                                                                                                                                                 |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | **OWN reads regress past 8t**: `read_thr08`=718M → `_thr16`=691M → `_thr32`=556M. Same shape on Mixed: rs=0.8 8t=110M → 16t=88M → 32t=92M (≈ −20%).                      | HIGH         | Report shows only 1-thread and 8-thread columns and hides the regression. Need explanation: 7800X3D is 8C/16T, so 16t = SMT pressure and 32t = oversubscription; volatile loads on every `Node.next`/`AtomicReferenceArray.get` make the hot path SMT-sensitive.                   |
| 2     | **OWN writes don't scale past 8t**: 1t=15.6M, 8t=34.5M (2.2×), 16t=30.6M, 32t=31.2M. JDK same pattern but 1t=15M → 8t=109M (7.3×) → flat.                                | HIGH         | Root cause: OWN uses **16 segments ×** `ReentrantLock` → max ≈16-way write parallelism + segment hot-spots. JDK 8+ CHM uses bucket-level CAS, no segment cap. Report only says "JDK scales much better" without naming the cause.                                                  |
| 3     | `readOwnSingle_thr01` **(72.6M) ≠** `read_thr01 OWN` **(90.3M)** — same OWN impl, ~24% delta.                                                                            | HIGH         | `ReadBenchmarkUnsafe` keeps both `own` and `unsafe` maps live in same @State → double working set, more cache pressure. Report lists both numbers in different tables without saying they disagree. Makes the "+4% OWN vs UNSAFE" bar in `unsafe_overhead_1thread.png` misleading. |
| 4     | **"UNSAFE" =** `java.util.HashMap` (see `src/main/kotlin/hashmap/UnsafeHashMap.kt`).                                                                                     | HIGH         | Naming implies sun.misc.Unsafe / off-heap. It's literally `HashMap` wrapped, single-threaded. Report nowhere notes this — every reader will read the bar chart wrong.                                                                                                              |
| 5     | `ReadLatencyBenchmark.getSample` **uses ONE fixed key** (set in `@Setup`, same int for whole iteration).                                                                 | HIGH         | This is a hot-key benchmark, not a representative latency test. ~20ns p50 measures: hash a constant + index + read one hot Node. Not "read latency" as report claims.                                                                                                              |
| 6     | **JDK Mixed (rs=0.8, 8t) = 136M** but naive harmonic of `Read_thr08`(862M)+`Write_thr08`(108M) at 80/20 predicts ~360M. ~60% gap.                                        | MED          | RNG cost (`r.nextDouble()` + `r.nextLong()` + branch) per op + cache effects of intermixed reads/writes. Should be flagged so reader doesn't assume Mixed numbers are wrong.                                                                                                       |
| 7     | **SYNC at 2t > 1t** in many cells (e.g. mixed rs=0.8: 1t=15.6M, 2t=18.6M, then 4t=10.1M).                                                                                | MED          | Biased-locking / single-fork warmup artifact; or 1t fork hits a colder JIT path. Worth a one-liner so reader trusts the rest.                                                                                                                                                      |
| 8     | `ReadBenchmark.read_thr01 JDK` **relErr=43%, SYNC=40%;** `_thr04 JDK`**=25%;** `_thr16 JDK`**=29%;** `_thr32 JDK`**=15%; scaling** `OWN@1e7`**=13%,** `OWN@3e8`**=16%**. | MED          | Report only calls out `read_thr01`/`_thr04`/`_thr16` JDK. `read_thr32 JDK`, `scalingRead OWN @1e7/3e8` also wide. Root cause = **only 2 forks** + fork-to-fork JIT/page-cache spread; with 2 forks the 99.9% CI half-width is huge. Note "would tighten with ≥5 forks".            |
| 9     | **Latency table uses sample-mode** `score` **(mean, 24.59ns OWN) but chart uses p50 (20ns)**.                                                                            | MED          | Two numbers, no reconciliation. Mean > median because of long tail (p99.99=1.8μs, p100=16.8μs). Tails are likely safepoints/GC pauses — not discussed.                                                                                                                             |
| 10    | **Scaling 1k OWN (1.02G) < 10k OWN (1.11G)** — smaller map slower.                                                                                                       | LOW          | Plausibly: 1k fits in single segment → bucket-array contention is concentrated on 8 threads; at 10k buckets spread better. Report says "cache- and metadata-dominated" without specifying.                                                                                         |


## **Script / chart bugs**

`scripts/plot_results.py`:

plot_results.pyLines 563-565

    plt.bar(x - w, p50s, width=w, label="p50")

    plt.bar(x, p99s, width=w, label="p99")

    plt.bar(x + w, p999s, width=w, label="p99.9")

- `plot_latency_percentiles` sets `label=` on each bar group but **never calls** `plt.legend()` → `latency_percentiles.png` has 3 unlabeled bar colors per impl. Confirmed visually: no legend on the rendered PNG. **Real bug.**
- `plot_speedup_vs_sync` Mixed panel: 36-entry legend overflows the panel; subplot titles overlap (`...log y)R...`). Visually unreadable for Mixed. Suggest:
  - one line per `(impl, rs)` only (drop method dimension; method is just `mixed_thrXX`),
  - move legend `bbox_to_anchor` outside axes,
  - use `figsize=(6*n, 6)` not `(5*n, 5)`.
- All throughput charts use `linestyle=""` (markers-only). Hard to read scaling trends across `[1,2,4,8,16,32]`. Connecting lines would make Read/Write/Mixed regressions obvious — exactly the discussion missing from the report.
- `plot_unsafe_overhead` labels OWN at "+4% vs UNSAFE" — true given the JSON, but doesn't note that `read_thr01 OWN` here comes from `ReadBenchmark` (own-only fixture) while UNSAFE comes from `ReadBenchmarkUnsafe` (own+unsafe fixture). Apples to oranges (see issue #3).

## **Methodology / lab-level**

- `UnsafeHashMap` **baseline is mis-named** — see issue #4. Rename to `PlainHashMap` or "single-threaded HashMap wrapper" in code + report + chart labels, or replace with a real Unsafe/off-heap version.
- `Int`**/*`*Long` **are boxed** through `IntLongMap` (`putM(k: Int, v: Long): Long?`). Boxing allocations dominate the write hot path on all four impls. Report doesn't mention; this can explain why 1-thread writes converge to ~15M ops/s across OWN/JDK/SYNC (all bottleneck on `Long.valueOf` and `Integer.valueOf`).
- `ReadLatencyBenchmark` see issue #5. Add `r.nextInt(range)` inside `getSample` or move it to a `@Setup(Level.Invocation)` to avoid hot-key.
- **Scaling uses** `entries.toInt()` **for** `@Param` — `300000000` fits in Int but `entries: Int` for a 300M-entry boxed-key/boxed-value map → ~16 GB just for `Integer`+`Long`+`Node`. Numbers at 100M / 300M are valid but heavily GC-bound; should note in report.
- `ScalingBenchmark` **annotation says** `iterations=2 warmup=1` but Gradle override forces `iterations=5 warmup=5` (`build.gradle.kts` lines 70-72). JSON confirms 5/5. The class-level annotation is dead code; can confuse readers. Either rely on annotations or remove them.
- **Flame graph** still placeholder (task acknowledges this). For writes plateau at 8t (the most interesting finding) a flame graph of `WriteBenchmark.write_thr16 OWN` vs `JDK` would directly show segment-lock contention vs CAS-spin. Task.md lists it as optional follow-up; leaving it out is OK but report should not present itself as "complete" until that's done — the TODO.md explicitly says "use jmh and generate flame graph".

## **Coverage checklist vs** `TODO.md`


| `TODO.md` **requirement**                   | **Status**                                                                                             |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| put / get / size / clear / merge / iterator | ✓ in `ConcurrentHashMap.kt`                                                                            |
| almost-never-blocking reads                 | ✓ (`AtomicReferenceArray` + `@Volatile` chain)                                                         |
| observable order between completed ops      | ✓ (segment lock for writes; volatile reads)                                                            |
| benchmarks vs non-thread-safe               | ✓ (`UnsafeHashMap`/`HashMap` baseline) — but mis-named                                                 |
| concurrency tests (jcstress)                | ✓ 4 stress tests                                                                                       |
| charts                                      | ✓ but speedup/latency charts have legend/clutter bugs                                                  |
| explain interesting results                 | **PARTIAL** — main regressions (OWN at >8t for read/write/mixed) and methodology caveats not in report |
| flame graph                                 | ✗ still placeholder                                                                                    |
| benchmarks up to 28 GB                      | ✓ ran at `-Xmx24g`, scaling up to 300M entries                                                         |


## **Recommended fixes (priority order)**

1. **Rewrite "Interpretation" sections** of `BENCHMARK_REPORT.md` to cover items #1, #2, #4, #5, #9. One paragraph each, citing JSON cells.
2. **Add full Read/Write/Mixed scaling tables** (1→32 threads, not just 1 and 8) so the >8t regression is visible.
3. **Fix** `plot_latency_percentiles`: add `plt.legend()` before `tight_layout()`. Re-render.
4. **Rewrite** `plot_speedup_vs_sync` **Mixed panel** to drop the `method` dimension from legend labels (use only `(impl, rs)`) and move legend outside.
5. **Switch throughput charts to lines+markers** (e.g. `linestyle="-"` or `marker="o", linestyle="-"`) — currently `linestyle=""`. Trends will read at a glance.
6. **Rename** `UnsafeHashMap` **→** `PlainHashMap` (or add a real Unsafe impl). Update README, MapSupport.kt, chart label `"UNSAFE"` → `"HashMap"`.
7. **Fix** `ReadLatencyBenchmark`: vary key per invocation, otherwise rename to `getHotKeyLatency` and note in report.
8. **Note 2-fork caveat** in the high-variance section; recommend ≥5 forks for a clean ranking.
9. (Optional) Generate one flame graph for `WriteBenchmark.write_thr16` OWN vs JDK to back up issue #2.

want me to implement the report rewrite + script bug fixes? if yes, say which subset.