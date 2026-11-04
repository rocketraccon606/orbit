#!/usr/bin/env bash
# Full local gate. Missing components are skipped so early phases still run.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
cd "$ORBIT_ROOT"

if [ -d curator/tests ]; then
  orbit_log "curator tests"
  "$PYTHON" -m pytest curator/tests -q
fi

if [ -f app/gradlew ]; then
  orbit_log "app unit tests"
  ./app/gradlew -p app :app:testDebugUnitTest --console=plain
  orbit_log "app debug build"
  ./app/gradlew -p app :app:assembleDebug --console=plain
  orbit_log "manifest + size audit"
  scripts/audit.sh
fi

orbit_log "check complete"
