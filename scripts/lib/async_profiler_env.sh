# shellcheck shell=bash
# Resolve async-profiler on Arch/CachyOS (AUR: async-profiler-bin → /opt/async-profiler).
# Source from record_flame.sh / meta_run_all.sh — do not execute directly.
#
# Install (once, user):
#   yay -S --needed async-profiler-bin
# Optional kernel settings for non-root CPU samples:
#   sudo sysctl kernel.perf_event_paranoid=1 kernel.kptr_restrict=0
#
# Optional auto-install when missing (non-interactive yay):
#   AUTO_INSTALL_ASYNC_PROFILER=1 ./scripts/record_flame.sh

async_profiler_install_hint() {
  echo "Install async-profiler (prebuilt, v4.x, /opt/async-profiler):" >&2
  echo "  yay -S --needed async-profiler-bin" >&2
  echo "Or set ASYNC_PROFILER_HOME to an unpacked release tarball." >&2
}

async_profiler_try_yay_install() {
  if [[ "${AUTO_INSTALL_ASYNC_PROFILER:-0}" != "1" ]]; then
    return 1
  fi
  if ! command -v yay >/dev/null 2>&1; then
    echo "AUTO_INSTALL_ASYNC_PROFILER=1 but yay not found." >&2
    return 1
  fi
  echo "==> Installing async-profiler-bin via yay ..."
  yay -S --needed --noconfirm async-profiler-bin
}

# Sets ASYNC_PROFILER_HOME and ASYNC_PROFILER_LIB; returns 0 if lib exists.
resolve_async_profiler() {
  local home="" lib=""

  if [[ -n "${ASYNC_PROFILER_HOME:-}" ]]; then
    home="${ASYNC_PROFILER_HOME%/}"
  elif [[ -d /opt/async-profiler/lib ]]; then
    home="/opt/async-profiler"
  elif command -v asprof >/dev/null 2>&1; then
    home="$(cd "$(dirname "$(readlink -f "$(command -v asprof)")")/.." && pwd)"
  fi

  if [[ -n "$home" && -f "$home/lib/libasyncProfiler.so" ]]; then
    lib="$home/lib/libasyncProfiler.so"
  fi

  if [[ -z "$lib" ]]; then
    async_profiler_try_yay_install || true
    if [[ -f /opt/async-profiler/lib/libasyncProfiler.so ]]; then
      home="/opt/async-profiler"
      lib="/opt/async-profiler/lib/libasyncProfiler.so"
    fi
  fi

  if [[ -z "$lib" || ! -f "$lib" ]]; then
    async_profiler_install_hint
    return 1
  fi

  export ASYNC_PROFILER_HOME="$home"
  export ASYNC_PROFILER_LIB="$lib"
  if [[ -x "$home/bin/asprof" ]]; then
    export ASYNC_PROFILER_BIN="$home/bin/asprof"
  elif command -v asprof >/dev/null 2>&1; then
    export ASYNC_PROFILER_BIN="$(command -v asprof)"
  fi
  return 0
}

async_profiler_warn_perf() {
  if [[ -r /proc/sys/kernel/perf_event_paranoid ]]; then
    local p
    p="$(cat /proc/sys/kernel/perf_event_paranoid)"
    if [[ "$p" -gt 1 ]]; then
      echo "WARN: kernel.perf_event_paranoid=$p (recommend 1 for CPU stacks):" >&2
      echo "  sudo sysctl kernel.perf_event_paranoid=1 kernel.kptr_restrict=0" >&2
    fi
  fi
}
