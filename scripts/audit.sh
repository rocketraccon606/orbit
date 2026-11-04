#!/usr/bin/env bash
# Fails if the APK declares background components, forbidden permissions or is too big.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
cd "$ORBIT_ROOT"

APK="app/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { orbit_log "audit: $APK missing - run scripts/build-app.sh first"; exit 1; }

AAPT2="$ANDROID_HOME/build-tools/34.0.0/aapt2.exe"
[ -f "$AAPT2" ] || AAPT2="$ANDROID_HOME/build-tools/34.0.0/aapt2"
[ -f "$AAPT2" ] || { orbit_log "audit: aapt2 not found"; exit 1; }

MANIFEST="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null)"
FAIL=0

for token in "E: service" "E: receiver" "E: provider" "android.permission.WAKE_LOCK" \
             "com.google.firebase" "com.google.android.gms.analytics"; do
  if printf '%s' "$MANIFEST" | grep -q "$token"; then
    orbit_log "FAIL: manifest contains $token"
    FAIL=1
  fi
done

PERMS="$(printf '%s' "$MANIFEST" | grep -o 'android.permission.[A-Z_]*' | sort -u || true)"
BAD_PERMS="$(printf '%s\n' "$PERMS" | grep -v -E '^android.permission.(INTERNET|ACCESS_NETWORK_STATE)$' || true)"
if [ -n "$BAD_PERMS" ]; then
  orbit_log "FAIL: unexpected permissions:"
  printf '%s\n' "$BAD_PERMS"
  FAIL=1
fi

SIZE="$(stat -c %s "$APK" 2>/dev/null || wc -c < "$APK")"
LIMIT=$((12 * 1024 * 1024))
orbit_log "apk bytes: $SIZE (budget $LIMIT)"
if [ "$SIZE" -gt "$LIMIT" ]; then
  orbit_log "FAIL: APK over 12 MB budget"
  FAIL=1
fi

if [ -f app/app/build/outputs/logs/dependencies.txt ]; then
  if grep -qE 'work-runtime|firebase|play-services-ads|play-services-measurement' \
      app/app/build/outputs/logs/dependencies.txt; then
    orbit_log "FAIL: forbidden background/analytics dependency present"
    FAIL=1
  fi
fi

[ "$FAIL" -eq 0 ] && orbit_log "audit PASS"
exit "$FAIL"
