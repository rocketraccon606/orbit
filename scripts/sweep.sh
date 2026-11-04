#!/usr/bin/env bash
# Rough channel sweep: cycle N channels and count switches vs playback failures.
# The debug app's sweep mode (prompts/07) is the precise version; this works today.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/device.sh"
cd "$ORBIT_ROOT"
mkdir -p artifacts

N="${1:-20}"; DWELL="${2:-8}"
orbit_resolve_device true
LOG="artifacts/sweep-$(date +%Y%m%d-%H%M%S).log"
adb -s "$DEVICE" logcat -c
adb -s "$DEVICE" shell am start -n app.orbit.tv/.MainActivity >/dev/null
sleep 4
adb -s "$DEVICE" shell input keyevent 23   # play focused channel
sleep "$DWELL"
for _ in $(seq 1 "$N"); do
  adb -s "$DEVICE" shell input keyevent 166
  sleep "$DWELL"
done
adb -s "$DEVICE" logcat -d -s OrbitPerf AndroidRuntime > "$LOG" || true

SWITCHES="$(grep -c 'OrbitPerf switch' "$LOG" || true)"
FAILS="$(grep -cE 'Unavailable|ExoPlaybackException|FATAL EXCEPTION' "$LOG" || true)"
TOTAL=$((SWITCHES + FAILS))
if [ "$TOTAL" -eq 0 ]; then
  orbit_log "no samples; is the app in the player?"
  exit 1
fi
"$PYTHON" -c "print(f'switches={$SWITCHES} failures={$FAILS} success={100.0*$SWITCHES/$TOTAL:.1f}%')"
orbit_log "saved $LOG"
