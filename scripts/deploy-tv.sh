#!/usr/bin/env bash
# Guarded sideload to the TCL TV. Never touches other packages or TV settings.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/device.sh"
cd "$ORBIT_ROOT"

ALLOW_EMU=false; NO_LOGS=false
for arg in "$@"; do
  case "$arg" in
    --allow-emulator) ALLOW_EMU=true ;;
    --no-logs) NO_LOGS=true ;;
    *) orbit_log "unknown option: $arg"; exit 2 ;;
  esac
done

orbit_resolve_device "$ALLOW_EMU"
"$ORBIT_ROOT/scripts/build-app.sh"

APK="app/app/build/outputs/apk/debug/app-debug.apk"
orbit_log "installing only app.orbit.tv on $DEVICE"
adb -s "$DEVICE" install -r "$APK"

START=(shell am start -n app.orbit.tv/.MainActivity)
if [ -n "${ORBIT_TV_CATALOG_URL:-}" ]; then
  START+=(--es catalog_base_url "$ORBIT_TV_CATALOG_URL")
fi
adb -s "$DEVICE" "${START[@]}"

adb -s "$DEVICE" shell dumpsys package app.orbit.tv | grep -E 'versionName|versionCode' | head -2 || true

if [ "$NO_LOGS" = false ]; then
  orbit_log "showing OrbitPerf/crash logs for 20 s (Ctrl-C to stop)"
  timeout 20 adb -s "$DEVICE" logcat -s OrbitPerf AndroidRuntime || true
fi
