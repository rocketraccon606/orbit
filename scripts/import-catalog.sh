#!/usr/bin/env bash
# Offline path: copy a locally built catalog into the debug app's files dir.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/device.sh"
cd "$ORBIT_ROOT"

CATALOG="${1:-$ORBIT_ROOT/curator/dist/catalog.db}"
[ -f "$CATALOG" ] || { orbit_log "catalog not found: $CATALOG"; exit 1; }

orbit_resolve_device true
orbit_log "copying $(basename "$CATALOG") into app.orbit.tv/files (debug build only)"
adb -s "$DEVICE" shell run-as app.orbit.tv mkdir -p files
adb -s "$DEVICE" shell run-as app.orbit.tv sh -c 'cat > files/catalog.db' < "$CATALOG"
adb -s "$DEVICE" shell am force-stop app.orbit.tv
adb -s "$DEVICE" shell am start -n app.orbit.tv/.MainActivity
orbit_log "imported; first frame should use the local catalog"
