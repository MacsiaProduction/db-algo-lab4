#!/usr/bin/env bash
# Full JMH + plots + jcstress — no async-profiler (use ./scripts/record_flame.sh separately).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
RESULTS_FULL="$ROOT/results/full"
mkdir -p "$RESULTS_FULL/graphs"

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 not found; plotting after JMH would fail." >&2
  exit 1
fi

if ! python3 - <<'PY'
import importlib.util
missing = [m for m in ("matplotlib", "numpy") if importlib.util.find_spec(m) is None]
if missing:
    raise SystemExit("missing: " + ", ".join(missing))
PY
then
  echo "==> Installing plotting dependencies before the long JMH run"
  python3 -m pip install -q -r scripts/requirements.txt
  python3 - <<'PY'
import importlib.util
missing = [m for m in ("matplotlib", "numpy") if importlib.util.find_spec(m) is None]
if missing:
    raise SystemExit("plotting dependencies still missing: " + ", ".join(missing))
PY
fi

echo "==> JMH → results/full/jmh-results.json"
./gradlew jmh --no-daemon \
  -Pjmh.resultsFile=results/full/jmh-results.json \
  "$@"

echo "==> Plot graphs"
JMH_RESULTS_JSON="$RESULTS_FULL/jmh-results.json" \
  JMH_PLOTS_DIR="$RESULTS_FULL/graphs" \
  python3 scripts/plot_results.py

echo "==> jcstress (optional)"
if [[ "${RUN_JCSTRESS:-1}" == "1" ]]; then
  ./gradlew jcstress --no-daemon || true
fi

echo "Done. Benchmarks: $RESULTS_FULL/jmh-results.json  plots: $RESULTS_FULL/graphs/"
echo "Flame (separate run): ./meta_run_flame.sh  or  ./scripts/record_flame.sh"
