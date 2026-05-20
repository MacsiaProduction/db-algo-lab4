#!/usr/bin/env bash
# =============================================================================
# AGENTS — meta_run_all.sh: benchmarks only (no flame / no profiler overhead)
# -----------------------------------------------------------------------------
# Run this first. For CPU/HNSW flame graphs, run ./meta_run_flame.sh afterward.
#
# Lab4: test → run_benchmarks.sh → results/full/{jmh-results.json,graphs/}
# Lab3: ./run_all.sh → LAB_SCALING_FULL=1 LAB_N_SWEEP=1281167 ./run_all.sh
#       (CSVs/plots under results/full/ and docs/img/full/ — lab3 layout)
#
# Lab3 needs ../db-algo-lab3 with .venv.
#
#   ./meta_run_all.sh
#
# Env: SKIP_LAB3=1 | RUN_LAB3_FULL=0 | JMH_HEAP=24g
# Log: results/logs/meta_run_all-<timestamp>.log
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB3_ROOT="$(cd "$ROOT/../db-algo-lab3" && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_DIR="$ROOT/results/logs"
LOG="$LOG_DIR/meta_run_all-${STAMP}.log"
mkdir -p "$ROOT/results/full/graphs" "$LOG_DIR"

exec > >(tee -a "$LOG") 2>&1

if [[ "${SKIP_LAB3:-0}" != "1" ]]; then
  if [[ ! -x "$LAB3_ROOT/run_all.sh" ]]; then
    echo "ERROR: $LAB3_ROOT/run_all.sh not found" >&2
    exit 1
  fi
  if [[ ! -x "$LAB3_ROOT/.venv/bin/jupyter" ]]; then
    echo "ERROR: Lab3 .venv missing" >&2
    echo "       In db-algo-lab3 run: python3 -m venv .venv && .venv/bin/pip install -r requirements.txt" >&2
    exit 1
  fi
  "$LAB3_ROOT/.venv/bin/python" - <<'PY'
import importlib.util
mods = ["faiss", "h5py", "matplotlib", "nbconvert", "numpy", "pandas", "psutil", "scipy", "seaborn", "tqdm"]
missing = [m for m in mods if importlib.util.find_spec(m) is None]
if missing:
    raise SystemExit("Lab3 .venv missing packages: " + ", ".join(missing))
PY
fi

echo "============================================================"
echo "  meta_run_all (benchmarks only — no flame): $(date)"
echo "  Lab4: $ROOT"
echo "  Lab3: $LAB3_ROOT"
echo "  Log:  $LOG"
echo "  Flames later: ./meta_run_flame.sh"
echo "============================================================"

cd "$ROOT"

echo ""
echo "==================== Lab4: ./gradlew test ===================="
./gradlew test --no-daemon -q

echo ""
echo "==================== Lab4: ./scripts/run_benchmarks.sh ========"
./scripts/run_benchmarks.sh --no-daemon -Pjmh.heap="${JMH_HEAP:-24g}"

echo ""
echo "==================== Lab4 done: $(date) ========================"
echo "  JSON:  $ROOT/results/full/jmh-results.json"
echo "  Plots: $ROOT/results/full/graphs/"

if [[ "${SKIP_LAB3:-0}" == "1" ]]; then
  echo ""
  echo "SKIP_LAB3=1 — skipping db-algo-lab3"
  echo "============================================================"
  echo "  meta_run_all finished: $(date)"
  echo "  Next: ./meta_run_flame.sh"
  echo "============================================================"
  exit 0
fi

export LAB_NOTEBOOK_TIMEOUT="${LAB_NOTEBOOK_TIMEOUT:-168h}"
export LAB_CELL_TIMEOUT="${LAB_CELL_TIMEOUT:-86400}"

cd "$LAB3_ROOT"

echo ""
echo "==================== Lab3: ./run_all.sh ========================"
./run_all.sh

if [[ "${RUN_LAB3_FULL:-1}" == "1" ]]; then
  echo ""
  echo "==================== Lab3: full base + scaling =============="
  LAB_SCALING_FULL=1 LAB_N_SWEEP=1281167 ./run_all.sh
fi

echo ""
echo "============================================================"
echo "  meta_run_all finished: $(date)"
echo "  Lab4: $ROOT/results/full/"
echo "  Lab3: $LAB3_ROOT/results/full/  plots: $LAB3_ROOT/docs/img/full/"
echo "  Next:  ./meta_run_flame.sh"
echo "============================================================"
