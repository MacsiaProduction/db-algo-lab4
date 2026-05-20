#!/usr/bin/env bash
# =============================================================================
# AGENTS — meta_run_flame.sh: targeted flame runs only (run after meta_run_all.sh)
# -----------------------------------------------------------------------------
# Does not re-run full JMH or lab3 notebooks. Small profiler-only workloads.
#
# Lab4: WriteBenchmark.write_thr16 OWN/JDK → results/flame/*.html
# Lab3: HNSW build sample → docs/img/full/hnsw_build_flame.svg
#
#   ./meta_run_flame.sh
#
# Env: SKIP_LAB3=1 | RUN_LAB4_FLAME=0 | RUN_LAB3_FLAME=0
#      LAB_N_SWEEP=1000000 (lab3 HNSW build size)
# Log: results/logs/meta_run_flame-<timestamp>.log
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAB3_ROOT="$(cd "$ROOT/../db-algo-lab3" && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_DIR="$ROOT/results/logs"
LOG="$LOG_DIR/meta_run_flame-${STAMP}.log"
mkdir -p "$ROOT/results/flame" "$LOG_DIR"

exec > >(tee -a "$LOG") 2>&1

if [[ "${SKIP_LAB3:-0}" != "1" && "${RUN_LAB3_FLAME:-1}" == "1" ]]; then
  if [[ ! -x "$LAB3_ROOT/scripts/record_flame.sh" ]]; then
    echo "ERROR: $LAB3_ROOT/scripts/record_flame.sh missing" >&2
    exit 1
  fi
  if [[ ! -x "$LAB3_ROOT/.venv/bin/python" ]]; then
    echo "ERROR: Lab3 .venv missing" >&2
    exit 1
  fi
  if [[ ! -x "$LAB3_ROOT/.venv/bin/py-spy" ]] && ! command -v py-spy >/dev/null 2>&1; then
    echo "ERROR: py-spy missing. Install with: $LAB3_ROOT/.venv/bin/pip install py-spy" >&2
    exit 1
  fi
fi

echo "============================================================"
echo "  meta_run_flame (profiler-only): $(date)"
echo "  Lab4: $ROOT/results/flame/"
echo "  Lab3: $LAB3_ROOT/docs/img/full/hnsw_build_flame.svg"
echo "  Log:  $LOG"
echo "============================================================"

cd "$ROOT"

if [[ "${RUN_LAB4_FLAME:-1}" == "1" ]]; then
  echo ""
  echo "==================== Lab4: ./scripts/record_flame.sh ========="
  ./scripts/record_flame.sh
else
  echo "RUN_LAB4_FLAME=0 — skip Lab4"
fi

if [[ "${SKIP_LAB3:-0}" == "1" ]]; then
  echo ""
  echo "SKIP_LAB3=1 — done"
  exit 0
fi

if [[ "${RUN_LAB3_FLAME:-1}" == "1" ]]; then
  echo ""
  echo "==================== Lab3: ./scripts/record_flame.sh ========="
  cd "$LAB3_ROOT"
  LAB_N_SWEEP="${LAB_N_SWEEP:-1000000}" ./scripts/record_flame.sh
fi

echo ""
echo "============================================================"
echo "  meta_run_flame finished: $(date)"
echo "  Lab4: $ROOT/results/flame/"
echo "  Lab3: $LAB3_ROOT/docs/img/flame/hnsw_build_flame.svg"
echo "============================================================"
