#!/usr/bin/env bash
# Scripted smoke test with screenshots. Modes: default, --home-only, --player.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/device.sh"
cd "$ORBIT_ROOT"
mkdir -p artifacts

MODE=default
[ "${1:-}" = "--home-only" ] && MODE=home
[ "${1:-}" = "--player" ] && MODE=player

orbit_resolve_device true
shot() { adb -s "$DEVICE" exec-out screencap -p > "artifacts/$1.png"; orbit_log "screenshot artifacts/$1.png"; }

adb -s "$DEVICE" shell am start -n app.orbit.tv/.MainActivity >/dev/null
sleep 4
shot "smoke-home"

if [ "$MODE" = "home" ]; then
  adb -s "$DEVICE" shell input keyevent 19 19 19
  sleep 1
  shot "smoke-home-focus"
  exit 0
fi

if [ "$MODE" = "player" ]; then
  adb -s "$DEVICE" shell input keyevent 23
  sleep 8
  shot "smoke-player"
  adb -s "$DEVICE" logcat -d -s OrbitPerf > artifacts/smoke-player.log || true
  adb -s "$DEVICE" shell input keyevent 4
  sleep 2
  shot "smoke-player-back"
  exit 0
fi

# Home focuses the first card; two Ups reach the top bar where Search sits.
adb -s "$DEVICE" shell input keyevent 19 19
sleep 1
adb -s "$DEVICE" shell input keyevent 23
sleep 2
shot "smoke-search"
adb -s "$DEVICE" shell input keyevent 4
sleep 1
shot "smoke-home-restored"
adb -s "$DEVICE" logcat -d -s OrbitPerf > artifacts/smoke.log || true
orbit_log "smoke complete"
