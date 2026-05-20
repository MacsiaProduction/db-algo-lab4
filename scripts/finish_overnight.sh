#!/usr/bin/env bash
# Finish Lab3 notebook 05 + flames for both labs (after meta_run_all benchmarks).
#
#   cd /path/to/db-algo-lab4
#   LAB_N_SWEEP=1281167 LAB_SCALING_FULL=1 ./scripts/finish_overnight.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAB3="$(cd "$ROOT/../db-algo-lab3" && pwd)"

cd "$LAB3"
LAB_N_SWEEP="${LAB_N_SWEEP:-1281167}" \
LAB_SCALING_FULL="${LAB_SCALING_FULL:-1}" \
  ./scripts/resume_from_05.sh

LAB_N_SWEEP="${LAB_FLAME_N_SWEEP:-1000000}" ./scripts/record_flame.sh

# Re-run 05 to embed flame SVG (reuse scaling.csv — no multi-hour rebuild)
SKIP_SCALING_REBUILD=1 ./scripts/resume_from_05.sh

cd "$ROOT"
./meta_run_flame.sh

echo "==> Overnight finish complete: $(date)"
