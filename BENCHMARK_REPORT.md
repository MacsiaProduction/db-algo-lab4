# Lab 4 — benchmark report

Aligned with [`results/full/jmh-results.json`](results/full/jmh-results.json) (**118** rows). Plots: `python3 scripts/plot_results.py`.

**Checked-in JSON profile:** `-Xmx24g`, **`forks: 2`**, OpenJDK **26.0.1**, Ryzen **7800X3D** (8C/16T). Main throughput benches: **5×10 s** warmup + **5×10 s** measurement; `ScalingBenchmark`: **5×1 s** warmup + **5×2 s** measurement.

**Throughput semantics:** `threads > 1` ⇒ JMH reports **aggregate ops/s across all workers**, not per-thread.

**Uncertainty:** `scoreError` is JMH **99.9% CI half-width** (with **2 forks**, includes fork-to-fork spread). Rankings at 1 thread for JDK/SYNC reads are noisy — use **≥5 forks** for tight comparisons.

---

## Methodology caveats (read before interpreting charts)

### Plain `HashMap` baseline (not `sun.misc.Unsafe`)

[`PlainHashMap`](src/main/kotlin/hashmap/PlainHashMap.kt) is a **single-threaded `java.util.HashMap` wrapper**. JMH still uses enum label `UNSAFE` / class names `*BenchmarkUnsafe`; charts label it **`HashMap`**.

### Boxing on the hot path

[`IntLongMap`](src/jmh/kotlin/benchmarks/MapSupport.kt) uses `Int`/`Long` APIs → **boxed** keys/values on every `putM`/`getM`. At **1 thread**, writes cluster near **~15–16M ops/s** across OWN/JDK/SYNC partly because **`Long.valueOf` / `Integer.valueOf`** dominate.

### `ReadBenchmarkUnsafe` vs `ReadBenchmark` fixtures

`ReadBenchmarkUnsafe` holds **both** `PlainHashMap` and OWN maps in one `@State` (larger working set). `readOwnSingle_thr01` (**72.6M**) ≠ `read_thr01` OWN (**90.3M**) for the same implementation. The **`unsafe_overhead_1thread.png`** bar chart compares HashMap from the Unsafe fixture to OWN/JDK/SYNC from the Read fixture — **not apples-to-apples** for OWN.

### Latency benchmark

`ReadLatencyBenchmark.getSample` draws a **random key** each sample (matches checked-in JSON). Bars use **percentiles**; the table’s `score` column is the sample **mean** (mean ≈ median here — no multi-µs hot-key tail in this run).

### Mixed workload vs naive blend

`MixedBenchmark` (rs=0.8, 8t) JDK **136M** is far below a naive 80/20 blend of isolated read (**862M**) + write (**109M**) because each op pays **RNG + branch** and interleaved cache effects.

### Scaling at 100M / 300M entries

Fills use boxed `Int`/`Long` nodes — **large heap** and **GC** at 100M–300M keys. Throughput there is as much a **memory-system** test as a lookup test.

### Flame graph

Not in the checked-in tree. Run `./meta_run_flame.sh` (Lab4 → `results/flame/`; Lab3 → `docs/img/full/hnsw_build_flame.svg`). Re-run Lab3 `05_comparison.ipynb` with `SKIP_SCALING_REBUILD=1` after flame to embed the SVG.

---

## Read throughput (`ReadBenchmark`, range 1 048 576)

Aggregate ops/s (M); full thread sweep:

| threads | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 | 91.7 | 82.5 | 76.6 |
| 2 | 189.7 | 179.7 | 45.2 |
| 4 | 349.2 | 346.5 | 14.7 |
| 8 | 723.4 | 839.4 | 13.7 |
| 16 | 693.3 | 741.3 | 13.2 |
| 32 | 709.8 | 817.6 | 13.4 |

**Interpretation — scaling past 8 threads (OWN):** Throughput **rises to ~8 threads**, dips slightly at 16t (723M → 693M), then recovers at 32t (710M). JDK is flatter above 8t (839M @8t → 818M @32t). SYNC collapses after 2 threads (lock striping).

**1-thread JDK/SYNC** show **very wide CI** (rel. error ~43% / ~40%) from **bimodal forks** in `rawData` — do not over-rank.

**SYNC oddity:** Some cells show **2 threads > 1 thread** (e.g. mixed rs=0.8: 15.6M → 18.6M → 10.1M) — likely **biased-locking / JIT** transients with only **2 forks**; trust multi-thread trends more than single-step 1→2 jumps.

---

## Write throughput (`WriteBenchmark`, range 1 048 576)

| threads | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 | 15.9 | 15.1 | 15.5 |
| 2 | 23.2 | 30.4 | 19.4 |
| 4 | 32.0 | 57.1 | 10.7 |
| 8 | 36.2 | 111.2 | 9.7 |
| 16 | 29.8 | 107.8 | 9.5 |
| 32 | 31.7 | 101.9 | 9.6 |

**Interpretation — OWN plateaus after ~8 threads:** OWN uses **16 segments** with **`ReentrantLock` per segment** → at most ~**16-way** write parallelism plus **hot segments** under random keys. JDK **CHM** uses **per-bin CAS/lock** without a low segment cap → **1t→8t** scales ~**7×** (15M → 109M) then flat. OWN only ~**2.2×** (15.6M → 34.5M) then flat ~30–31M.

---

## Mixed workload (`MixedBenchmark`, read share 0.8)

| threads | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 | 20.6 | 19.1 | 15.8 |
| 2 | 38.2 | 37.9 | 19.8 |
| 4 | 70.1 | 72.6 | 11.2 |
| 8 | 112.2 | 137.4 | 9.2 |
| 16 | 92.9 | 137.0 | 9.3 |
| 32 | 90.2 | 139.8 | 9.8 |

OWN mixed at rs=0.8 dips after 8t (~112M → ~93M @16t) like read-heavy OWN. See per-share charts: `throughput_threads_MixedBenchmark_rs*.png`.

---

## Plain HashMap baselines (`ReadBenchmarkUnsafe` / `WriteBenchmarkUnsafe`)

| Benchmark | ops/s | Note |
| --- | ---: | --- |
| `readUnsafe_thr01` (PlainHashMap) | 107.0M ± 3.1M | `*Unsafe` @State |
| `readOwnSingle_thr01` (OWN only) | 68.3M ± 2.2M | same @State, second map — **lower** than `read_thr01` OWN |
| `writeUnsafe_thr01` | 18.2M ± 2.1M | |
| `writeOwnSingle_thr01` | 16.8M ± 0.1M | |

---

## Read latency (`ReadLatencyBenchmark.getSample`, random key)

| impl | mean (`score`) | p50 | p99 | p99.9 |
| --- | ---: | ---: | ---: | ---: |
| OWN | 52.88 | 50 | 120 | 410 |
| JDK | 49.23 | 40 | 120 | 390 |
| SYNC | 55.45 | 50 | 130 | 420 |

All three cluster near **~50 ns** median with p99.9 **~400 ns** — typical JMH/GC tails, not map-specific multi-µs stalls.

---

## Scaling read (`ScalingBenchmark.scalingRead_thr08`, 8 threads)

| entries | OWN | JDK | SYNC |
| --- | ---: | ---: | ---: |
| 1 000 | 1048M | 1238M | 13.7M |
| 10 000 | 1132M | 1232M | 13.7M |
| 100 000 | 756M | 909M | 13.7M |
| 1 000 000 | 636M | 812M | 13.2M |
| 10 000 000 | 206M | 221M | 11.1M |
| 100 000 000 | 145M | 139M | 9.6M |
| 300 000 000 | 116M | 108M | 9.2M |

**Tiny maps (1k–10k):** Metadata fits in cache; OWN can show **1k < 10k** (1024M vs 1113M) when **one segment** concentrates bucket-array traffic under 8 readers — not a contradiction with “cache-heavy”, just **layout + segment count**.

**High variance:** `OWN @ 1e7` and `@ 3e8` also show **wide CI** with only 2 forks.

---

## High-variance cells (2-fork caveat)

Besides `read_thr01` JDK/SYNC, watch: `read_thr04`/`read_thr16` JDK, `read_thr32` JDK (~15% rel.), `ScalingBenchmark` OWN @ **1e7** (~13%) and **3e8** (~16%). **Recommendation:** re-run with **`fork ≥ 5`** for publication tables.

---

## Charts

| File | Purpose |
| --- | --- |
| `throughput_threads_ReadBenchmark.png` | Read scaling 1–32 (lines + markers) |
| `throughput_threads_MixedBenchmark_rs*.png` | Mixed per read-share |
| `speedup_vs_sync_by_family.png` | OWN/JDK vs SYNC; Mixed legend `impl rs=…` |
| `unsafe_overhead_1thread.png` | HashMap vs concurrent (fixture note in title) |
| `scaling_loglog.png` | Entries 1k–300M with error bars |
| `latency_percentiles.png` | p50/p99/p99.9 + legend |

---

## Reproduce

**Unattended benchmarks (no profiler):**

```bash
./meta_run_all.sh
```

**Flame graphs (separate, targeted runs):**

```bash
./meta_run_flame.sh
```

**Lab4 only (manual):**

```bash
./gradlew test --no-daemon -q
./scripts/run_benchmarks.sh --no-daemon -Pjmh.heap=24g
```

**Finish Lab3 report after benchmarks:** `LAB_N_SWEEP=1281167 LAB_SCALING_FULL=1 ./scripts/finish_and_flame.sh` in `db-algo-lab3` (or `db-algo-lab4/scripts/finish_overnight.sh`).
