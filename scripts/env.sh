#!/usr/bin/env bash
# Local toolchain for Orbit. Source this, do not execute it.
#   source scripts/env.sh

ORBIT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORBIT_TOOLCHAIN="${ORBIT_TOOLCHAIN:-$(cd "$ORBIT_ROOT/../toolchain" && pwd)}"

to_win() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

export ORBIT_ROOT
export ORBIT_TOOLCHAIN
export JAVA_HOME="$(to_win "$ORBIT_TOOLCHAIN/jdk17/jdk-17.0.20.1+1")"
export ANDROID_HOME="$(to_win "$ORBIT_TOOLCHAIN/android-sdk")"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ORBIT_TOOLCHAIN/jdk17/jdk-17.0.20.1+1/bin:$ORBIT_TOOLCHAIN/android-sdk/platform-tools:$ORBIT_TOOLCHAIN/android-sdk/emulator:$ORBIT_TOOLCHAIN/android-sdk/cmdline-tools/latest/bin:$PATH"

# AVG Web Shield intercepts TLS with a root the JDK truststore does not contain.
# Windows-ROOT reads the OS store (already used by curl/Python) and is local-only.
[[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]] && \
  export GRADLE_OPTS="${GRADLE_OPTS:-} -Djavax.net.ssl.trustStoreType=Windows-ROOT"

export PYTHON="$ORBIT_TOOLCHAIN/python/python.exe"
export UV="$ORBIT_TOOLCHAIN/uv/uv.exe"
export PYTHONPATH="$ORBIT_ROOT/curator${PYTHONPATH:+:$PYTHONPATH}"
export PYTHONUTF8=1

# Android TV emulator created by scripts/emulator.sh.
export ORBIT_EMULATOR="${ORBIT_EMULATOR:-orbit_tv_api30}"
# Set to the TV serial reported by `adb devices` before running deploy-tv.sh.
export ORBIT_SERIAL="${ORBIT_SERIAL:-}"
# Where debug builds fetch the catalog during local development.
export ORBIT_CATALOG_URL="${ORBIT_CATALOG_URL:-http://10.0.2.2:8787/}"
export ORBIT_CATALOG_PORT="${ORBIT_CATALOG_PORT:-8787}"

orbit_log() { printf '[orbit] %s\n' "$*"; }
