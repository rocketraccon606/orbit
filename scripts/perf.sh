#!/usr/bin/env bash
# Sample memory while surfing channels; writes a CSV for leak checks.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/device.sh"
cd "$ORBIT_ROOT"
mkdir -p artifacts

SAMPLES="${1:-30}"; INTERVAL="${2:-10}"
orbit_resolve_device true
OUT="artifacts/meminfo-$(date +%Y%m%d-%H%M%S).csv"
echo "sample,epoch,total_pss_kb,total_mem_kb" > "$OUT"

for i in $(seq 1 "$SAMPLES"); do
  MEM="$(adb -s "$DEVICE" shell dumpsys meminfo app.orbit.tv | tr -d '\r')"
  PSS="$(printf '%s\n' "$MEM" | awk '/TOTAL PSS:/{print $1; exit} /^ *TOTAL/{print $2; exit}')"
  TOTAL="$(printf '%s\n' "$MEM" | awk '/TOTAL:/{print $2; exit}')"
  echo "$i,$(date +%s),${PSS:-},${TOTAL:-}" >> "$OUT"
  orbit_log "sample $i: pss=${PSS:-?} kB"
  adb -s "$DEVICE" shell input keyevent 166 >/dev/null
  sleep "$INTERVAL"
done
orbit_log "saved $OUT"
