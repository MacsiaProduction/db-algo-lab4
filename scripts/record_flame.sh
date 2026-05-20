#!/usr/bin/env bash
# =============================================================================
# AGENTS — Lab4 flame-only run (no full JMH matrix). Run AFTER meta_run_all.sh.
# -----------------------------------------------------------------------------
# Profiles only WriteBenchmark.write_thr16 @ 16 threads (OWN + JDK).
# Writes to results/flame/ — never overwrites results/full/ or results/light/.
#
#   ./scripts/record_flame.sh
#   ./meta_run_flame.sh          # Lab4 + Lab3 flames
#
# Prereqs: async-profiler (yay -S --needed async-profiler-bin → /opt/async-profiler)
# =============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/async_profiler_env.sh
source "$ROOT/scripts/lib/async_profiler_env.sh"
cd "$ROOT"
FLAME_DIR="$ROOT/results/flame"
mkdir -p "$FLAME_DIR"

JMH_HEAP="${JMH_HEAP:-24g}"
BENCH="${FLAME_BENCH:-WriteBenchmark.write_thr16}"

resolve_async_profiler || exit 1
async_profiler_warn_perf

echo "==> async-profiler: $ASYNC_PROFILER_HOME"
echo "==> output dir: $FLAME_DIR (isolated from results/full/)"

profile_impl() {
  local impl="$1"
  local html="$FLAME_DIR/write_thr16_${impl}.html"
  local json="$FLAME_DIR/jmh-write_thr16_${impl}.json"
  echo "==> JMH + agent — ${BENCH} impl=${impl}"
  echo "    html: $html"
  echo "    json: $json (profile run only; not used for report plots)"
  "$ROOT/gradlew" -p "$ROOT" jmh --no-daemon \
    -Pjmh.heap="$JMH_HEAP" \
    -Pjmh.resultsFile="results/flame/jmh-write_thr16_${impl}.json" \
    -Pjmh.profile=true \
    -Pjmh.includes="$BENCH" \
    -Pjmh.impl="$impl" \
    -Pjmh.flameFile="$html" \
    -Pjmh.asyncProfilerLib="$ASYNC_PROFILER_LIB"
}

profile_impl OWN
profile_impl JDK

echo "==> Lab4 flame artifacts: $FLAME_DIR/"
