#!/usr/bin/env bash
# Measure channel switches from key press to first frame using OrbitPerf logs.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/device.sh"
cd "$ORBIT_ROOT"
mkdir -p artifacts

N=20; COLD=false; SLEEP=2.5
while [ $# -gt 0 ]; do
  case "$1" in
    --cold) COLD=true ;;
    --sleep) SLEEP="$2"; shift ;;
    *) N="$1" ;;
  esac
  shift
done

orbit_resolve_device true
OUT="artifacts/bench-$(date +%Y%m%d-%H%M%S).txt"

if [ "$COLD" = true ]; then
  : > "$OUT"
  for i in 1 2 3; do
    adb -s "$DEVICE" shell am force-stop app.orbit.tv
    adb -s "$DEVICE" logcat -c
    adb -s "$DEVICE" shell am start -n app.orbit.tv/.MainActivity >/dev/null
    sleep 4
    adb -s "$DEVICE" logcat -d -s OrbitPerf >> "$OUT" || true
  done
else
  adb -s "$DEVICE" logcat -c
  adb -s "$DEVICE" shell am start -n app.orbit.tv/.MainActivity >/dev/null
  sleep 3
  # Home focuses the first card; OK starts playback so CH+/CH- has a current channel.
  adb -s "$DEVICE" shell input keyevent 23
  sleep 8
  for _ in $(seq 1 "$N"); do
    adb -s "$DEVICE" shell input keyevent 166   # KEYCODE_CHANNEL_UP
    sleep "$SLEEP"
  done
  adb -s "$DEVICE" logcat -d -s OrbitPerf > "$OUT" || true
fi

"$PYTHON" - "$OUT" <<'PY'
import re, statistics, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
switch = [int(m) for m in re.findall(r"switch\b.*?\bms=(\d+)", text)]
cold = [int(m) for m in re.findall(r"coldstart\b.*?\bms=(\d+)", text)]
for label, values in (("switch", switch), ("coldstart", cold)):
    if not values:
        print(f"{label}: no samples (is the app running and logging OrbitPerf?)")
        continue
    values.sort()
    p90 = values[min(len(values) - 1, int(round(0.9 * (len(values) - 1))))]
    over = sum(1 for v in values if v > 3000)
    print(f"{label}: n={len(values)} median={statistics.median(values):.0f}ms "
          f"p90={p90}ms min={values[0]}ms max={values[-1]}ms over3s={over}")
    if label == "switch" and (statistics.median(values) >= 1500 or p90 >= 3000):
        print("TARGET MISSED: median<1500 and p90<3000 required")
sys.exit(0 if switch or cold else 1)
PY
orbit_log "saved $OUT"
