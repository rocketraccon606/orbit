#!/usr/bin/env bash
# Resolve exactly one authorized device, or fail with a human-readable reason.
# Sourced by deploy/bench/smoke scripts.
# Sets DEVICE (serial) and DEVICE_KIND (emulator|tv).

orbit_resolve_device() {
  local allow_emu="${1:-false}"
  local rows
  rows="$(adb devices | tail -n +2 | sed '/^[[:space:]]*$/d')"

  if printf '%s\n' "$rows" | grep -q 'unauthorized'; then
    orbit_log "ADB device is unauthorized. Accept the RSA prompt on the TV, then rerun."
    return 2
  fi
  if printf '%s\n' "$rows" | grep -q 'offline'; then
    orbit_log "ADB device is offline. Reconnect it, then rerun."
    return 2
  fi

  if [ -n "${ORBIT_SERIAL:-}" ]; then
    if ! printf '%s\n' "$rows" | grep -q "^${ORBIT_SERIAL}[[:space:]]*device"; then
      orbit_log "ORBIT_SERIAL=$ORBIT_SERIAL is not connected and authorized."
      printf '%s\n' "$rows"
      return 2
    fi
    DEVICE="$ORBIT_SERIAL"
  else
    local devices
    devices="$(printf '%s\n' "$rows" | awk '$2=="device"{print $1}')"
    local count
    count="$(printf '%s\n' "$devices" | grep -c . || true)"
    if [ "$count" -ne 1 ]; then
      orbit_log "Expected exactly one authorized device, found $count. Set ORBIT_SERIAL."
      printf '%s\n' "$rows"
      return 2
    fi
    DEVICE="$devices"
  fi

  case "$DEVICE" in
    emulator-*) DEVICE_KIND=emulator ;;
    *) DEVICE_KIND=tv ;;
  esac

  if [ "$DEVICE_KIND" = "emulator" ] && [ "$allow_emu" != "true" ]; then
    orbit_log "Refusing to deploy to an emulator without --allow-emulator."
    return 2
  fi
  export DEVICE DEVICE_KIND
  orbit_log "target: $DEVICE ($DEVICE_KIND)"
}
