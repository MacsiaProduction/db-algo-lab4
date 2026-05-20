# Task summary — Lab 4 benchmarks, charts, and report

This file captures the user’s prompts, constraints, requirements, and the work requested across the conversation (plan: [review-results-charts](.cursor/plans/review-results-charts_6af5c66c.plan.md); do not edit the plan file itself).

---

## User prompts (chronological)

1. **Implement “Fix Charts and Code” plan**  
   - Follow attached plan; do **not** edit the plan file.  
   - Use existing todos; mark **in_progress** from the first; complete all todos.

2. **Run full test and put result in report**  
   - Run the full test/benchmark pipeline and update the report with actual numbers.

3. **Command for full JMH with 24 GB heap**  
   - Provide the Gradle command to run the full JMH suite capped at 24 GB heap.

4. **Review full result and improve charts**  
   - Review full JMH results for problems and inconsistencies (including strange gaps in charts).  
   - Fix issues and improve charts.  
   - **Add timeouts to commands** so long runs do not hang the session.

5. **Implement “Review Results And Improve Charts” plan** (same as #1, second pass)  
   - Same rules: no plan edits, todos already created, complete all.

6. **Summarize to `task.md`** (this file)  
   - Summarize previous prompts, remarks, requirements, and tasks in one place.

---

## Standing requirements and remarks

| Requirement | Detail |
|-------------|--------|
| **Do not edit plan files** | `.cursor/plans/*.plan.md` are reference only. |
| **Todos** | Use existing todo IDs; mark `in_progress` while working; complete all before stopping. |
| **Timeouts on shell** | e.g. `timeout 10m ./gradlew test`, `timeout 30s python3 scripts/plot_results.py`, `timeout 6h ./gradlew jmh ...` for long JMH. |
| **No empty commits** | Only commit when the user asks (user rules). |
| **Root cause before fixes** | Plot/report bugs were treated as grouping and documentation issues, not hashmap correctness. |
| **Canonical data** | `results/full/jmh-results.json` is the source of truth for tables and plots; report must match it. |

---

## Original “Fix Charts and Code” goals (lab plan)

1. **`@Volatile` on `Segment.buckets`** in `ConcurrentHashMap.kt` for resize visibility under concurrent `get`.
2. **`ResizeStressTest`** jcstress (1 segment × 1 bucket, put/get under resize).
3. **`build.gradle.kts`**: `jcstress { mode = "quick" }`; extend `jmh.light` with UNSAFE read/write benchmarks.
4. **`plot_results.py`**: error bars, per-family speedup, unsafe overhead chart, latency percentile keys, mixed heatmap fallback, scaling skip message.
5. **`BENCHMARK_REPORT.md`**: environment, tables, analysis, placeholders for scaling/flamegraph.

*(Most of the above was done in an earlier session; the “review results” pass focused on alignment with the **actual** 118-row JSON and chart grouping.)*

---

## “Review Results And Improve Charts” — problems found

### Data / report

- **`results/full/jmh-results.json`**: **118 rows** (not 112); **`-Xmx24g`**, **`forks: 2`**, **5×10 s** warmup/measurement for Read/Write/Mixed; Scaling uses **5×1 s** warmup, **5×2 s** measurement; includes scaling **`entriesStr` 100M and 300M**.
- **`BENCHMARK_REPORT.md`** had been written for a different profile (8 g, 1 fork, 112 rows, scaling capped at 1e7) — numbers and metadata did not match JSON.
- **High `scoreError`** on some cells (e.g. `ReadBenchmark.read_thr01` JDK/SYNC) due to large fork-to-fork spread — must be called out, not over-interpreted.

### Charts (`scripts/plot_results.py`)

- **`plot_throughput_by_threads`**: **MixedBenchmark** overwrote three `readShareStr` values per `(impl, thread)`; **ScalingBenchmark** overwrote seven `entriesStr` at fixed `thr08` → misleading single-point or zig-zag “scaling vs threads” chart.
- **`plot_speedup_vs_sync`**: Mixed lines lacked **`readShareStr`** → multiple points at same **x** (thread) → vertical gaps in PNG.
- **`throughput_threads_ScalingBenchmark.png`**: wrong x-axis (thread vs entries).
- Subagent audit: additional risks (zero error bars when missing, log scale, latency `score` vs percentiles, color mapping for unsafe bars, etc.) — several addressed in the implementation pass.

---

## Implementation tasks (review pass) — what to do / what was done

### 1. Audit JSON (`audit-current-json`)

- Validate row count, duplicate `(benchmark, params)` keys, high relative `scoreError`.
- **`validate_jmh_rows()`** in `plot_results.py` logs warnings to stderr.

### 2. Fix plot grouping (`fix-plot-grouping`)

- **Read/Write only** in generic `plot_throughput_by_threads`; skip Mixed and Scaling.
- **`plot_mixed_throughput_by_read_share`**: `throughput_threads_MixedBenchmark_rs0.2|0.5|0.8.png`.
- **`plot_speedup_vs_sync`**: Mixed labels include `rs=`; log Y; markers-only lines between points.
- **`plot_scaling_loglog`**: dedupe `(impl, entries)`, error bars, thread count from JSON.
- **`plot_unsafe_overhead`**: fixed label→color map.
- Remove obsolete **`throughput_threads_MixedBenchmark.png`** and **`throughput_threads_ScalingBenchmark.png`**.
- **`assert_speedup_mixed_no_duplicate_x`** after plotting.

### 3. Regenerate graphs (`regenerate-graphs`)

```bash
timeout 60s python3 scripts/plot_results.py
```

### 4. Update report (`update-report`)

- Rewrite **`BENCHMARK_REPORT.md`** from **118-row** JSON: 24 g, 2 forks, full scaling table (through 300M), corrected Read/Write/Mixed/UNSAFE/latency numbers, high-variance notes, chart file list.

### 5. Verify with timeouts (`verify-timeboxed`)

```bash
timeout 15m ./gradlew test --no-daemon -q
timeout 60s python3 scripts/plot_results.py
```

### 6. `build.gradle.kts`

- Remove ineffective **`benchmarkParameters.put("entriesStr", ...)`** under **`jmh.report`**; comment that scaling still follows `@Param` in sources.

---

## Commands reference

| Purpose | Command |
|---------|---------|
| Unit tests | `timeout 15m ./gradlew test --no-daemon -q` |
| Full JMH (24 GB heap) | `timeout 6h ./gradlew jmh --no-daemon -Pjmh.heap=24g` |
| Shorter report profile | `./gradlew jmh --no-daemon -Pjmh.report=true -Pjmh.heap=8g` |
| Smoke / light | `./gradlew jmh -Pjmh.light=true -Pjmh.heap=1536m` |
| Plots | `timeout 60s python3 scripts/plot_results.py` |
| jcstress | `timeout 30m ./gradlew jcstress --no-daemon` |
| Custom JSON | `JMH_RESULTS_JSON=/path/to.json python3 scripts/plot_results.py` |

---

## Key artifacts

| Path | Role |
|------|------|
| `results/full/jmh-results.json` | Canonical JMH export (118 rows in current repo) |
| `results/graphs/*.png` | Generated plots |
| `BENCHMARK_REPORT.md` | Human-readable analysis aligned with JSON |
| `scripts/plot_results.py` | Plotting + validation warnings |
| `build.gradle.kts` | JMH / jcstress / `jmh.light` / `jmh.report` profiles |

---

## Optional follow-ups (not required unless user asks)

- Script or CI check that **`BENCHMARK_REPORT.md`** tables stay in sync with **`jmh-results.json`**.
- Address remaining plot-audit items (strict UTF-8 load, omit error bars when any point lacks error, verify `scoreUnit` on latency chart).
- Re-run full **`./gradlew jmh -Pjmh.heap=24g`** after code changes and refresh JSON + report + plots in one step.
- Flame graph section under `results/flamegraph/` (still placeholder in report).
