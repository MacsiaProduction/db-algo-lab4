# Lab 4 — benchmark report

This document matches the **checked-in** JMH export [`results/jmh-results.json`](results/jmh-results.json) (**118** result rows). Plots were generated with `python3 scripts/plot_results.py` (the script prints warnings for duplicate keys and for `scoreError` > 25% of score).

## Machine and environment (from JSON)

| Field | Value |
| --- | --- |
| Host OS | Linux 7.0.6 (CachyOS), x86_64 |
| CPU | AMD Ryzen 7 7800X3D 8-Core Processor |
| JMH | 1.37 |
| JVM | OpenJDK 64-Bit Server VM 26.0.1 |
| JDK | 26.0.1 |
| Heap | `-Xmx24g` `-Xms256m` `-XX:+UseG1GC` (as recorded in each row’s `jvmArgs`) |
| Throughput benchmarks (`ReadBenchmark`, `WriteBenchmark`, `MixedBenchmark`, unsafe thrpt) | **`forks`: 2**, **5** warmup × **10 s**, **5** measurement × **10 s** |
| `ScalingBenchmark.scalingRead_thr08` | **`forks`: 2**, **5** warmup × **1 s**, **5** measurement × **2 s** |
| `ReadLatencyBenchmark.getSample` | **`forks`: 2**, sample mode (see JSON for warmup/measurement times) |

**Throughput semantics:** For `threads > 1`, JMH **throughput** is **aggregate ops/s over all worker threads** (not per-thread). Compare implementations at the same thread count.

**Uncertainty:** `scoreError` is JMH’s **99.9% CI half-width** on the primary score (with **2 forks**, variance includes fork-to-fork effects). Some cells show **very wide** intervals (see below)—treat those headline numbers cautiously.

### High-variance cells (wide CI vs score)

From the same JSON, relative `scoreError`/score exceeds **25%** for:

- `ReadBenchmark.read_thr01`, **`impl=JDK`** and **`impl=SYNC`** — large fork-to-fork spread; do not over-interpret single-number “winner” at 1 thread for JDK/SYNC here.
- `ReadBenchmark.read_thr04` / `read_thr16`, **`impl=JDK`** — elevated CI vs other JDK read points.

`scripts/plot_results.py` logs similar rows to stderr whenever you regenerate plots.

## How to reproduce (with timeouts)

```bash
timeout 15m ./gradlew test --no-daemon -q
timeout 6h ./gradlew jmh --no-daemon -Pjmh.heap=24g   # full matrix; long
timeout 60s python3 scripts/plot_results.py
```

For a **shorter** matrix (still all benchmark classes from source, but shorter per-iteration times / 1 fork), use `-Pjmh.report=true` (see [`build.gradle.kts`](build.gradle.kts)). For a **smoke** subset, use `-Pjmh.light=true`.

## Charts (after `plot_results.py`)

| Output | What it shows |
| --- | --- |
| `throughput_threads_ReadBenchmark.png`, `throughput_threads_WriteBenchmark.png` | Throughput vs threads; **Mixed** and **Scaling** are **not** folded in here (different parameter dimensions). |
| `throughput_threads_MixedBenchmark_rs0.2.png` (and `rs0.5`, `rs0.8`) | Mixed workload **per read-share** so lines are not overwritten. |
| `throughput_threads_*Unsafe*.png` | UNSAFE / single-thread reference maps. |
| `speedup_vs_sync_by_family.png` | Read / Write / Mixed families; Mixed lines include **`rs=`** read-share in the legend; **log y** for ratio. |
| `scaling_loglog.png` | Entries sweep (all `entriesStr` in JSON, including **100M** and **300M**) with **error bars** where `scoreError` is finite. |
| `unsafe_overhead_1thread.png` | 1-thread UNSAFE vs OWN/JDK/SYNC. |
| `latency_percentiles.png` | Sample-time latency percentiles (`p50` / `p99` / `p99.9`) + whisker note. |
| `mixed_heatmap_*.png` | Read-share × threads heatmap per `impl`. |

## Read throughput (`ReadBenchmark`, `rangeStr` = 1 048 576)

Aggregate ops/s; ± is JMH `scoreError`.

| impl | 1 thread (`read_thr01`) | 8 threads (`read_thr08`) |
| --- | --- | --- |
| OWN | 90.3M ± 2.5M | 718.2M ± 1.9M |
| JDK | 89.6M ± 38.8M | 862.1M ± 28.4M |
| SYNC | 63.6M ± 25.6M | 13.4M ± 0.2M |

**Interpretation:** At 8 threads, OWN and JDK remain in the same ballpark; SYNC collapses (global lock). At 1 thread, JDK/SYNC show **very wide CIs** in this run—compare distributions / more forks if you need a tight ranking.

## UNSAFE baselines (`ReadBenchmarkUnsafe` / `WriteBenchmarkUnsafe`)

| Benchmark | ops/s |
| --- | --- |
| `readUnsafe_thr01` | 86.6M ± 2.0M |
| `readOwnSingle_thr01` | 72.6M ± 6.5M |
| `writeUnsafe_thr01` | 19.5M ± 0.1M |
| `writeOwnSingle_thr01` | 16.6M ± 0.3M |

See `unsafe_overhead_1thread.png` for grouped comparison with concurrent maps at 1 thread.

## Write throughput (`WriteBenchmark`, `rangeStr` = 1 048 576)

| impl | 1 thread (`write_thr01`) | 8 threads (`write_thr08`) |
| --- | --- | --- |
| OWN | 15.6M ± 0.3M | 34.5M ± 0.9M |
| JDK | 15.0M ± 0.1M | 108.8M ± 1.1M |
| SYNC | 15.5M ± 0.1M | 9.4M ± 0.1M |

**Interpretation:** Single-thread writes are similar. At 8 threads JDK scales much better under concurrent writes; SYNC is limited by the synchronized wrapper.

## Mixed workload (`MixedBenchmark`, `readShareStr` = **0.8**, **8 threads**)

| impl | ops/s |
| --- | --- |
| OWN | 109.9M ± 5.6M |
| JDK | 136.2M ± 3.8M |
| SYNC | 9.1M ± 0.2M |

Other read shares and thread counts: per-share line charts (`throughput_threads_MixedBenchmark_rs*.png`) and heatmaps.

## Read latency (`ReadLatencyBenchmark.getSample`, sample time)

| impl | ns/op | ± (`scoreError`) |
| --- | ---: | ---: |
| OWN | 24.59 | 0.45 |
| JDK | 21.66 | 0.41 |
| SYNC | 24.50 | 0.23 |

Bars in `latency_percentiles.png` use **`scorePercentiles`** (`p50` / `p99` / `p99.9`), not the raw `score` column (which is a different aggregate in sample mode).

## Scaling read (`ScalingBenchmark.scalingRead_thr08`, **8 threads**, aggregate ops/s)

| entries | OWN | JDK | SYNC |
| --- | --- | --- | --- |
| 1 000 | 1024M | 1224M | 13.4M |
| 10 000 | 1113M | 1260M | 13.1M |
| 100 000 | 766M | 919M | 13.2M |
| 1 000 000 | 702M | 829M | 12.5M |
| 10 000 000 | 214M | 223M | 11.1M |
| 100 000 000 | 132M | 135M | 9.6M |
| 300 000 000 | 120M | 107M | 9.2M |

Very small maps are cache- and metadata-dominated; treat **≥ 1e6** (or larger) as more representative of steady-state tree/table behavior. **`scaling_loglog.png`** includes error bars and the full entries sweep.

## CPU / flame graph

**Placeholder:** Capture async-profiler (or similar) on e.g. `WriteBenchmark.write_thr08` for OWN vs JDK and store under `results/flamegraph/`.

## jcstress

```bash
timeout 30m ./gradlew jcstress --no-daemon
```

Resize visibility is guarded by `ResizeStressTest` and related tests (`jcstress { mode = "quick" }` in `build.gradle.kts`).
