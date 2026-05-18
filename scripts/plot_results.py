#!/usr/bin/env python3
"""
Parse JMH JSON output and emit plots under results/graphs/.
"""
from __future__ import annotations

import json
import math
import os
import re
import sys
import warnings
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JSON = ROOT / "results" / "jmh-results.json"
OUT_DIR = ROOT / "results" / "graphs"

# Obsolete filenames from older plotting logic (avoid stale graphs in results/graphs/)
OBSOLETE_GRAPH_NAMES = frozenset(
    {
        "throughput_threads_MixedBenchmark.png",
        "throughput_threads_ScalingBenchmark.png",
    },
)


def load_rows(path: Path) -> list[dict]:
    if not path.exists():
        print(f"Missing JMH results: {path}", file=sys.stderr)
        return []
    text = path.read_text(encoding="utf-8", errors="replace").strip()
    if not text:
        return []
    if text.startswith("["):
        return json.loads(text)
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def thread_from_benchmark(name: str) -> int | None:
    m = re.search(r"_thr(\d+)$", name)
    return int(m.group(1)) if m else None


def thread_from_row(r: dict) -> int | None:
    """Prefer JMH top-level threads when present."""
    t = r.get("threads")
    if isinstance(t, int) and t > 0:
        return t
    return thread_from_benchmark(r.get("benchmark", ""))


def bench_class(name: str) -> str:
    """e.g. benchmarks.ReadBenchmark.read_thr01 -> ReadBenchmark"""
    parts = name.split(".")
    return parts[-2] if len(parts) >= 2 else name


def bench_method(name: str) -> str:
    return name.split(".")[-1]


def param_tuple_excluding_impl(params: dict) -> tuple[tuple[str, str], ...]:
    return tuple(sorted((k, str(v)) for k, v in params.items() if k != "impl"))


def parse_error(pm: dict) -> float | None:
    e = pm.get("scoreError")
    if e is None:
        return None
    if isinstance(e, str) and e.lower() == "nan":
        return None
    try:
        v = float(e)
    except (TypeError, ValueError):
        return None
    if math.isnan(v):
        return None
    return v


def ratio_error(a: float, da: float | None, b: float, db: float | None) -> float | None:
    """Delta-method uncertainty on ratio a/b; da, db are JMH scoreError (CI half-width, not paired)."""
    if da is None or db is None or b == 0 or a == 0:
        return None
    rel = math.sqrt((da / a) ** 2 + (db / b) ** 2)
    return (a / b) * rel


def validate_jmh_rows(rows: list[dict]) -> None:
    """Lightweight sanity checks: duplicate result keys, high relative scoreError."""
    by_key: dict[tuple[str, tuple[tuple[str, str], ...]], list[dict]] = defaultdict(list)
    for r in rows:
        b = r.get("benchmark", "")
        params = r.get("params") or {}
        key = (b, tuple(sorted((k, str(v)) for k, v in params.items())))
        by_key[key].append(r)

    dup = [k for k, v in by_key.items() if len(v) > 1]
    if dup:
        print(
            f"validate_jmh_rows: warning — {len(dup)} duplicate (benchmark,params) keys "
            f"(first: {dup[0][0]})",
            file=sys.stderr,
        )

    hi = []
    for r in rows:
        if r.get("mode") != "thrpt":
            continue
        pm = r.get("primaryMetric") or {}
        sc = pm.get("score")
        err = parse_error(pm)
        if sc is None or err is None:
            continue
        try:
            scf = float(sc)
        except (TypeError, ValueError):
            continue
        if scf > 0 and err / scf > 0.25:
            hi.append((r.get("benchmark"), r.get("params"), err / scf))
    hi.sort(key=lambda x: -x[2])
    for item in hi[:12]:
        print(
            f"validate_jmh_rows: high relative scoreError ({item[2]*100:.1f}% of score): "
            f"{item[0]} params={item[1]}",
            file=sys.stderr,
        )


def remove_obsolete_graphs(out: Path) -> None:
    for name in OBSOLETE_GRAPH_NAMES:
        p = out / name
        if p.exists():
            try:
                p.unlink()
                print(f"Removed obsolete graph {p}", file=sys.stderr)
            except OSError as e:
                print(f"Could not remove {p}: {e}", file=sys.stderr)


def _errorbar_yerr(errs: list[float | None]) -> list[float] | None:
    if not any(e is not None for e in errs):
        return None
    return [e if e is not None else 0.0 for e in errs]


def plot_throughput_by_threads(rows: list[dict], out: Path) -> None:
    """Throughput vs threads for Read/Write (+Unsafe). Skips Mixed/Scaling (different x semantics)."""
    by_class: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        if r.get("mode") != "thrpt":
            continue
        cls = bench_class(r.get("benchmark", ""))
        if cls in ("MixedBenchmark", "ScalingBenchmark"):
            continue
        by_class[cls].append(r)

    for cls, fr in sorted(by_class.items()):
        series: dict[str, dict[int, tuple[float, float | None]]] = defaultdict(dict)
        for r in fr:
            b = r.get("benchmark", "")
            thr = thread_from_row(r)
            if thr is None:
                continue
            pm = r.get("primaryMetric") or {}
            score = pm.get("score")
            if score is None:
                continue
            err = parse_error(pm)
            impl = (r.get("params") or {}).get("impl")
            if impl:
                series[impl][thr] = (float(score), err)
            elif cls == "ReadBenchmarkUnsafe":
                if "readUnsafe" in b:
                    series["UNSAFE"][thr] = (float(score), err)
                elif "readOwnSingle" in b:
                    series["OWN@1t-ref"][thr] = (float(score), err)
            elif cls == "WriteBenchmarkUnsafe":
                if "writeUnsafe" in b:
                    series["UNSAFE"][thr] = (float(score), err)
                elif "writeOwnSingle" in b:
                    series["OWN@1t-ref"][thr] = (float(score), err)

        if not series:
            continue

        plt.figure(figsize=(11, 6))
        for impl, pts in sorted(series.items()):
            xs = sorted(pts.keys())
            ys = [pts[t][0] for t in xs]
            yerr = [pts[t][1] for t in xs]
            ye = _errorbar_yerr(yerr)
            if ye is not None:
                plt.errorbar(xs, ys, yerr=ye, marker="o", capsize=3, label=impl, linestyle="")
            else:
                plt.plot(xs, ys, marker="o", label=impl, linestyle="")
        plt.xlabel("Threads")
        plt.ylabel("Throughput (ops/s)")
        plt.title(f"{cls}: throughput vs threads")
        plt.yscale("log")
        plt.grid(True, which="both", ls="--", alpha=0.4)
        plt.legend()
        plt.tight_layout()
        safe = re.sub(r"[^a-zA-Z0-9_-]+", "_", cls)
        plt.savefig(out / f"throughput_threads_{safe}.png", dpi=150)
        plt.close()


def plot_mixed_throughput_by_read_share(rows: list[dict], out: Path) -> None:
    """MixedBenchmark: one PNG per readShareStr (avoids collapsing 0.2/0.5/0.8 onto one x)."""
    fr = [
        r
        for r in rows
        if r.get("mode") == "thrpt" and bench_class(r.get("benchmark", "")) == "MixedBenchmark"
    ]
    by_rs: dict[str, list[dict]] = defaultdict(list)
    for r in fr:
        rs = str((r.get("params") or {}).get("readShareStr", ""))
        by_rs[rs].append(r)

    for rs, lst in sorted(by_rs.items(), key=lambda kv: float(kv[0]) if kv[0].replace(".", "").isdigit() else 0):
        series: dict[str, dict[int, tuple[float, float | None]]] = defaultdict(dict)
        for r in lst:
            b = r.get("benchmark", "")
            thr = thread_from_row(r)
            if thr is None:
                continue
            pm = r.get("primaryMetric") or {}
            sc = pm.get("score")
            if sc is None:
                continue
            impl = (r.get("params") or {}).get("impl")
            if not impl:
                continue
            series[impl][thr] = (float(sc), parse_error(pm))

        if not series:
            continue

        plt.figure(figsize=(11, 6))
        for impl, pts in sorted(series.items()):
            xs = sorted(pts.keys())
            ys = [pts[t][0] for t in xs]
            yerr = [pts[t][1] for t in xs]
            ye = _errorbar_yerr(yerr)
            if ye is not None:
                plt.errorbar(xs, ys, yerr=ye, marker="o", capsize=3, label=impl, linestyle="")
            else:
                plt.plot(xs, ys, marker="o", label=impl, linestyle="")
        plt.xlabel("Threads")
        plt.ylabel("Throughput (ops/s)")
        plt.title(f"MixedBenchmark readShare={rs}: throughput vs threads")
        plt.yscale("log")
        plt.grid(True, which="both", ls="--", alpha=0.4)
        plt.legend()
        plt.tight_layout()
        safe_rs = re.sub(r"[^0-9.]+", "_", rs).strip("_") or "na"
        plt.savefig(out / f"throughput_threads_MixedBenchmark_rs{safe_rs}.png", dpi=150)
        plt.close()


def plot_speedup_vs_sync(rows: list[dict], out: Path) -> None:
    """One subplot per benchmark class (Read / Write / Mixed): speedup vs SYNC, one point per (method,thread,params)."""
    thrpt = [r for r in rows if r.get("mode") == "thrpt"]
    by_class: dict[str, list[dict]] = defaultdict(list)
    for r in thrpt:
        cls = bench_class(r.get("benchmark", ""))
        if cls in ("ReadBenchmark", "WriteBenchmark", "MixedBenchmark"):
            by_class[cls].append(r)

    if not by_class:
        return

    n = len(by_class)
    fig, axes = plt.subplots(1, n, figsize=(5 * n, 5), squeeze=False)
    ax_flat = axes.flatten()

    for ax_idx, (cls, fr) in enumerate(sorted(by_class.items())):
        ax = ax_flat[ax_idx]
        grouped: dict[tuple, dict[str, tuple[float, float | None]]] = defaultdict(dict)
        for r in fr:
            impl = (r.get("params") or {}).get("impl")
            if not impl:
                continue
            b = r.get("benchmark", "")
            thr = thread_from_row(r)
            if thr is None:
                continue
            pm = r.get("primaryMetric") or {}
            sc = pm.get("score")
            if sc is None:
                continue
            params_ex = param_tuple_excluding_impl(r.get("params") or {})
            key = (bench_method(b), thr, params_ex)
            grouped[key][impl] = (float(sc), parse_error(pm))

        line_data: dict[str, list[tuple[int, float, float | None]]] = defaultdict(list)
        for key, scores in grouped.items():
            method, thr, params_ex = key
            sync = scores.get("SYNC")
            if not sync or sync[0] <= 0:
                continue
            rs = dict(params_ex).get("readShareStr", "")
            for impl in ("OWN", "JDK"):
                s = scores.get(impl)
                if s is None:
                    continue
                ratio = s[0] / sync[0]
                rerr = ratio_error(s[0], s[1], sync[0], sync[1])
                if cls == "MixedBenchmark" and rs:
                    label = f"{impl}.{method} rs={rs}"
                else:
                    label = f"{impl}.{method}"
                line_data[label].append((thr, ratio, rerr))

        for label, pts in sorted(line_data.items()):
            pts = sorted(pts, key=lambda x: x[0])
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            errs = [p[2] for p in pts]
            ye = _errorbar_yerr(errs)
            if ye is not None:
                ax.errorbar(xs, ys, yerr=ye, marker="o", capsize=3, label=label, linestyle="")
            else:
                ax.plot(xs, ys, marker="o", label=label, linestyle="")
        ax.axhline(1.0, color="gray", ls="--", linewidth=1)
        ax.set_xlabel("Threads")
        ax.set_ylabel("Throughput / SYNC")
        ax.set_yscale("log")
        ax.set_title(f"{cls}: speedup vs synchronized HashMap (log y)")
        ax.grid(True, ls="--", alpha=0.4)
        ax.legend(fontsize=7)

    plt.tight_layout()
    plt.savefig(out / "speedup_vs_sync_by_family.png", dpi=150)
    plt.close()


def _find_thrpt(
    rows: list[dict],
    *,
    bench_cls: str,
    method_suffix: str,
    impl: str | None,
) -> tuple[float, float | None] | None:
    matches: list[tuple[float, float | None]] = []
    for r in rows:
        if r.get("mode") != "thrpt":
            continue
        b = r.get("benchmark", "")
        if bench_class(b) != bench_cls or not b.endswith(method_suffix):
            continue
        p = r.get("params") or {}
        if impl is None:
            if p.get("impl"):
                continue
        elif p.get("impl") != impl:
            continue
        pm = r.get("primaryMetric") or {}
        sc = pm.get("score")
        if sc is None:
            continue
        matches.append((float(sc), parse_error(pm)))
    if len(matches) > 1:
        print(
            f"_find_thrpt: warning — multiple rows for {bench_cls} {method_suffix} impl={impl}; using first",
            file=sys.stderr,
        )
    return matches[0] if matches else None


def plot_unsafe_overhead(rows: list[dict], out: Path) -> None:
    """Single-thread read and write: UNSAFE vs OWN vs JDK vs SYNC (ops/s) + overhead % vs UNSAFE."""
    label_colors = {
        "UNSAFE": "#c44e52",
        "OWN": "#4c72b0",
        "JDK": "#55a868",
        "SYNC": "#8172b2",
    }
    read_labels = ["UNSAFE", "OWN", "JDK", "SYNC"]
    read_vals = [
        _find_thrpt(rows, bench_cls="ReadBenchmarkUnsafe", method_suffix="readUnsafe_thr01", impl=None),
        _find_thrpt(rows, bench_cls="ReadBenchmark", method_suffix="read_thr01", impl="OWN"),
        _find_thrpt(rows, bench_cls="ReadBenchmark", method_suffix="read_thr01", impl="JDK"),
        _find_thrpt(rows, bench_cls="ReadBenchmark", method_suffix="read_thr01", impl="SYNC"),
    ]
    write_labels = ["UNSAFE", "OWN", "JDK", "SYNC"]
    write_vals = [
        _find_thrpt(rows, bench_cls="WriteBenchmarkUnsafe", method_suffix="writeUnsafe_thr01", impl=None),
        _find_thrpt(rows, bench_cls="WriteBenchmark", method_suffix="write_thr01", impl="OWN"),
        _find_thrpt(rows, bench_cls="WriteBenchmark", method_suffix="write_thr01", impl="JDK"),
        _find_thrpt(rows, bench_cls="WriteBenchmark", method_suffix="write_thr01", impl="SYNC"),
    ]

    if all(v is None for v in read_vals) and all(v is None for v in write_vals):
        print("plot_unsafe_overhead: no matching read_thr01/write_thr01 data", file=sys.stderr)
        return

    fig, axes = plt.subplots(1, 2, figsize=(12, 5))

    def draw_bars(ax, labels: list[str], vals: list[tuple[float, float | None] | None], title: str) -> None:
        scores = [v[0] if v else float("nan") for v in vals]
        errs = [v[1] if v else None for v in vals]
        mask = [not math.isnan(s) for s in scores]
        if not any(mask):
            ax.set_visible(False)
            return
        plot_labels = [labels[i] for i in range(len(labels)) if mask[i]]
        plot_scores = [scores[i] for i in range(len(labels)) if mask[i]]
        plot_errs = [errs[i] for i in range(len(labels)) if mask[i]]
        colors = [label_colors.get(lab, "#888888") for lab in plot_labels]
        ax.bar(
            range(len(plot_labels)),
            plot_scores,
            yerr=[e if e is not None else 0 for e in plot_errs],
            capsize=3,
            color=colors,
        )
        ax.set_xticks(range(len(plot_labels)))
        ax.set_xticklabels(plot_labels, rotation=15)
        ax.set_ylabel("ops/s")
        ax.set_title(title)
        ax.grid(True, axis="y", ls="--", alpha=0.4)
        try:
            ui = plot_labels.index("UNSAFE")
        except ValueError:
            ui = None
        unsafe_score = plot_scores[ui] if ui is not None else None
        if unsafe_score and unsafe_score > 0:
            for i, (lab, sc) in enumerate(zip(plot_labels, plot_scores)):
                if lab == "UNSAFE":
                    continue
                ov = (sc / unsafe_score - 1.0) * 100.0
                ax.annotate(
                    f"{ov:+.0f}%",
                    xy=(i, sc),
                    xytext=(0, 4),
                    textcoords="offset points",
                    ha="center",
                    fontsize=8,
                )

    draw_bars(axes[0], read_labels, read_vals, "Read @ 1 thread (thr01)")
    draw_bars(axes[1], write_labels, write_vals, "Write @ 1 thread (thr01)")
    fig.suptitle("UNSAFE baseline vs concurrent maps (higher ops/s is better)")
    plt.tight_layout()
    plt.savefig(out / "unsafe_overhead_1thread.png", dpi=150)
    plt.close()


def plot_scaling_loglog(rows: list[dict], out: Path) -> None:
    pts: dict[str, dict[float, tuple[float, float | None]]] = defaultdict(dict)
    thread_label = "8"
    for r in rows:
        b = r.get("benchmark", "")
        if "ScalingBenchmark" not in b or r.get("mode") != "thrpt":
            continue
        impl = (r.get("params") or {}).get("impl")
        ent_s = (r.get("params") or {}).get("entriesStr")
        if not impl or not ent_s:
            continue
        try:
            ent = float(ent_s)
        except (TypeError, ValueError):
            print(f"plot_scaling_loglog: skip bad entriesStr={ent_s!r}", file=sys.stderr)
            continue
        pm = r.get("primaryMetric") or {}
        score = pm.get("score")
        if score is None:
            continue
        thr = thread_from_row(r)
        if thr is not None:
            thread_label = str(thr)
        prev = pts[impl].get(ent)
        scf, err = float(score), parse_error(pm)
        if prev is not None and prev[0] != scf:
            print(
                f"plot_scaling_loglog: duplicate (impl={impl}, entries={ent}); overwriting",
                file=sys.stderr,
            )
        pts[impl][ent] = (scf, err)

    if not pts:
        print("plot_scaling_loglog: no ScalingBenchmark rows; skip scaling_loglog.png", file=sys.stderr)
        return

    plt.figure(figsize=(10, 6))
    for impl, ent_map in sorted(pts.items()):
        data = sorted(ent_map.items(), key=lambda x: x[0])
        xs = np.array([d[0] for d in data])
        ys = np.array([d[1][0] for d in data])
        yerr = [d[1][1] for d in data]
        ye = _errorbar_yerr(yerr)
        if ye is not None:
            plt.errorbar(xs, ys, yerr=ye, marker="o", capsize=3, label=impl, linestyle="-")
        else:
            plt.plot(xs, ys, marker="o", label=impl, linestyle="-")
    plt.xscale("log")
    plt.yscale("log")
    plt.xlabel("Entries (keys)")
    plt.ylabel(f"Throughput (ops/s), {thread_label} threads read")
    plt.title("Scaling read throughput (log-log); tiny maps are cache-heavy")
    plt.grid(True, which="both", ls="--", alpha=0.4)
    plt.legend()
    plt.tight_layout()
    plt.savefig(out / "scaling_loglog.png", dpi=150)
    plt.close()


def plot_latency_percentiles(rows: list[dict], out: Path) -> None:
    lat_rows = [r for r in rows if "ReadLatencyBenchmark" in r.get("benchmark", "")]
    if not lat_rows:
        return

    impl_p: dict[str, dict[str, float]] = defaultdict(dict)
    for r in lat_rows:
        if r.get("mode") != "sample":
            continue
        params = r.get("params") or {}
        impl = params.get("impl")
        if not impl:
            continue
        key = impl
        pm = r.get("primaryMetric") or {}
        pct = pm.get("scorePercentiles") or {}
        p0 = pct.get("0.0")
        p50 = pct.get("50.0")
        p99 = pct.get("99.0")
        p999 = pct.get("99.9")
        p100 = pct.get("100.0")
        if p50 is None:
            continue
        impl_p[key]["p0"] = float(p0) if p0 is not None else float(p50)
        impl_p[key]["p50"] = float(p50)
        impl_p[key]["p99"] = float(p99) if p99 is not None else float(p50)
        impl_p[key]["p999"] = float(p999) if p999 is not None else float(p99 or p50)
        impl_p[key]["p100"] = float(p100) if p100 is not None else float(p999 or p50)

    labels = sorted(impl_p.keys())
    if not labels:
        return

    p50s = [impl_p[l]["p50"] for l in labels]
    p99s = [impl_p[l]["p99"] for l in labels]
    p999s = [impl_p[l]["p999"] for l in labels]

    x = np.arange(len(labels))
    w = 0.25
    plt.figure(figsize=(max(9, 1.2 * len(labels)), 5))
    plt.bar(x - w, p50s, width=w, label="p50")
    plt.bar(x, p99s, width=w, label="p99")
    plt.bar(x + w, p999s, width=w, label="p99.9")
    plt.xticks(x, labels, rotation=20, ha="right")
    plt.ylabel("Latency (ns/op)")
    plt.title("Read latency (sample time, log scale)")
    plt.yscale("log")
    note = "  ".join(f"{lab}: p0={impl_p[lab]['p0']:.0f} p100={impl_p[lab]['p100']:.0f}" for lab in labels)
    ax = plt.gca()
    ax.text(0.02, 0.98, note, transform=ax.transAxes, va="top", fontsize=7, family="monospace")
    plt.grid(True, axis="y", which="both", ls="--", alpha=0.4)
    plt.tight_layout()
    plt.savefig(out / "latency_percentiles.png", dpi=150)
    plt.close()


def plot_mixed_heatmap(rows: list[dict], out: Path) -> None:
    by_impl: dict[str, dict[tuple[float, int], float]] = defaultdict(dict)
    for r in rows:
        b = r.get("benchmark", "")
        if "MixedBenchmark" not in b or r.get("mode") != "thrpt":
            continue
        impl = (r.get("params") or {}).get("impl")
        rs = (r.get("params") or {}).get("readShareStr")
        thr = thread_from_row(r)
        score = (r.get("primaryMetric") or {}).get("score")
        if not impl or not rs or thr is None or score is None:
            continue
        by_impl[impl][(float(rs), thr)] = float(score)

    for impl, cells in by_impl.items():
        if not cells:
            continue
        ratios = sorted({c[0] for c in cells.keys()})
        threads = sorted({c[1] for c in cells.keys()})

        safe_impl = re.sub(r"[^a-zA-Z0-9_-]+", "_", impl)
        if len(threads) <= 1:
            plt.figure(figsize=(8, 4))
            xs = np.arange(len(ratios))
            ys = [cells.get((r, threads[0]), float("nan")) for r in ratios]
            plt.bar(xs, ys, tick_label=[str(r) for r in ratios])
            plt.xlabel("Read share")
            plt.ylabel("ops/s")
            plt.title(f"Mixed workload (single thread count={threads[0]}) — {impl}")
            plt.grid(True, axis="y", ls="--", alpha=0.4)
            plt.tight_layout()
            plt.savefig(out / f"mixed_heatmap_{safe_impl}.png", dpi=150)
            plt.close()
            continue

        mat = np.zeros((len(ratios), len(threads)))
        for i, ratio in enumerate(ratios):
            for j, thr in enumerate(threads):
                mat[i, j] = cells.get((ratio, thr), np.nan)
        plt.figure(figsize=(10, 4))
        plt.imshow(mat, aspect="auto", cmap="viridis")
        plt.colorbar(label="ops/s")
        plt.yticks(range(len(ratios)), [str(r) for r in ratios])
        plt.xticks(range(len(threads)), [str(t) for t in threads])
        plt.xlabel("Threads (categorical)")
        plt.ylabel("Read share")
        plt.title(f"Mixed workload throughput — {impl}")
        plt.tight_layout()
        plt.savefig(out / f"mixed_heatmap_{safe_impl}.png", dpi=150)
        plt.close()


def assert_speedup_mixed_no_duplicate_x(rows: list[dict]) -> None:
    """Sanity check: each Mixed speedup line has at most one point per thread."""
    thrpt = [r for r in rows if r.get("mode") == "thrpt" and bench_class(r.get("benchmark", "")) == "MixedBenchmark"]
    line_thr: dict[str, set[int]] = defaultdict(set)
    grouped: dict[tuple, dict[str, tuple[float, float | None]]] = defaultdict(dict)
    for r in thrpt:
        impl = (r.get("params") or {}).get("impl")
        if not impl:
            continue
        b = r.get("benchmark", "")
        thr = thread_from_row(r)
        if thr is None:
            continue
        pm = r.get("primaryMetric") or {}
        sc = pm.get("score")
        if sc is None:
            continue
        params_ex = param_tuple_excluding_impl(r.get("params") or {})
        key = (bench_method(b), thr, params_ex)
        grouped[key][impl] = (float(sc), parse_error(pm))

    for key, scores in grouped.items():
        method, thr, params_ex = key
        sync = scores.get("SYNC")
        if not sync or sync[0] <= 0:
            continue
        rs = dict(params_ex).get("readShareStr", "")
        for impl in ("OWN", "JDK"):
            if impl not in scores:
                continue
            label = f"{impl}.{method} rs={rs}" if rs else f"{impl}.{method}"
            if thr in line_thr[label]:
                raise RuntimeError(f"duplicate speedup x for {label} thr={thr}")
            line_thr[label].add(thr)


def main() -> int:
    src = Path(os.environ.get("JMH_RESULTS_JSON", DEFAULT_JSON))
    rows = load_rows(src)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    if not rows:
        print("No data to plot.", file=sys.stderr)
        return 1

    validate_jmh_rows(rows)
    remove_obsolete_graphs(OUT_DIR)

    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        plot_throughput_by_threads(rows, OUT_DIR)
        plot_mixed_throughput_by_read_share(rows, OUT_DIR)
        plot_speedup_vs_sync(rows, OUT_DIR)
        assert_speedup_mixed_no_duplicate_x(rows)
        plot_unsafe_overhead(rows, OUT_DIR)
        plot_scaling_loglog(rows, OUT_DIR)
        plot_latency_percentiles(rows, OUT_DIR)
        plot_mixed_heatmap(rows, OUT_DIR)
    print(f"Wrote plots under {OUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
