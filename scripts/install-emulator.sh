#!/usr/bin/env bash
# Install the debug APK on the local emulator and point it at the local catalog.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
cd "$ORBIT_ROOT"

"$ORBIT_ROOT/scripts/emulator.sh"
SERIAL="$(adb devices | grep '^emulator-' | grep 'device$' | head -1 | cut -f1)"
[ -n "$SERIAL" ] || { orbit_log "no emulator device"; exit 1; }

"$ORBIT_ROOT/scripts/build-app.sh"
adb -s "$SERIAL" install -r app/app/build/outputs/apk/debug/app-debug.apk

orbit_log "launching with catalog $ORBIT_CATALOG_URL"
adb -s "$SERIAL" shell am force-stop app.orbit.tv
adb -s "$SERIAL" shell am start -n app.orbit.tv/.MainActivity \
  --es catalog_base_url "$ORBIT_CATALOG_URL"
