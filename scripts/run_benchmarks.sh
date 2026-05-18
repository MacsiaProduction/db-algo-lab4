#!/usr/bin/env bash
# Run JMH, optional jcstress, optional async-profiler flame graph, then plot graphs.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p results/graphs results/flamegraph

echo "==> JMH (configure heap in build.gradle.kts jmh.jvmArgsAppend; use -Pjmh.includes=... to filter)"
./gradlew jmh --no-daemon "$@"

echo "==> Plot graphs from results/jmh-results.json"
if command -v python3 >/dev/null 2>&1; then
  python3 -m pip install -q -r scripts/requirements.txt 2>/dev/null || true
  python3 scripts/plot_results.py
else
  echo "python3 not found; skip plots"
fi

echo "==> jcstress (optional; may take a long time in tough mode)"
if [[ "${RUN_JCSTRESS:-1}" == "1" ]]; then
  ./gradlew jcstress --no-daemon || true
fi

# Flame graph: install async-profiler and point ASYNC_PROFILER_HOME at it, then profile a short JMH fork:
#   export ASYNC_PROFILER_HOME=/path/to/async-profiler
#   $ASYNC_PROFILER_HOME/profiler.sh -e cpu -d 60 -f results/flamegraph/cpu.html -- \
#     ./gradlew jmh --no-daemon -Pjmh.includes='ReadBenchmark.read_thr08'
if [[ -n "${ASYNC_PROFILER_HOME:-}" && -x "${ASYNC_PROFILER_HOME}/profiler.sh" ]]; then
  echo "==> async-profiler (60s CPU) on ReadBenchmark.read_thr08 (short forks via --args)"
  "${ASYNC_PROFILER_HOME}/profiler.sh" -e cpu -d 60 -f "$ROOT/results/flamegraph/cpu.html" -- \
    "$ROOT/gradlew" -p "$ROOT" jmh --no-daemon \
    --args='-e ReadBenchmark.read_thr08 -f 1 -wi 1 -i 1' || true
fi

echo "Done. See results/jmh-results.json, results/graphs/, results/flamegraph/"
