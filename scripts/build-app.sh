#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
cd "$ORBIT_ROOT"
mkdir -p artifacts

CATALOG_ARGS=()
if [ -n "${ORBIT_CATALOG_URL:-}" ]; then
  CATALOG_ARGS+=("-PorbitCatalogBaseUrl=$ORBIT_CATALOG_URL")
fi
./app/gradlew -p app :app:assembleDebug "${CATALOG_ARGS[@]}" --console=plain

APK="app/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { orbit_log "APK not found"; exit 1; }
SIZE="$(stat -c %s "$APK" 2>/dev/null || wc -c < "$APK")"
SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
orbit_log "apk: $ORBIT_ROOT/$APK"
orbit_log "bytes: $SIZE  sha256: $SHA"
if [ "$SIZE" -gt $((12 * 1024 * 1024)) ]; then
  orbit_log "WARNING: APK exceeds the 12 MB budget"
fi
