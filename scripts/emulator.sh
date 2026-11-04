#!/usr/bin/env bash
# Create and start the Android TV API 30 emulator used for pre-device testing.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
cd "$ORBIT_ROOT"
mkdir -p artifacts

IMAGE="system-images;android-30;android-tv;x86"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager.bat"
ANDROID_CLI="$ANDROID_HOME/cmdline-tools/latest/bin/android.exe"
[ -f "$SDKMANAGER" ] || SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[ -f "$AVDMANAGER" ] || AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
[ -f "$ANDROID_CLI" ] || ANDROID_CLI=""

if [ ! -d "$ANDROID_HOME/emulator" ] || [ ! -d "$ANDROID_HOME/platform-tools" ]; then
  orbit_log "installing emulator (one time)"
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  if [ -n "$ANDROID_CLI" ]; then
    "$ANDROID_CLI" sdk install emulator platform-tools
  else
    "$SDKMANAGER" "emulator" "platform-tools"
  fi
fi

if [ ! -d "$ANDROID_HOME/system-images/android-30/android-tv/x86" ]; then
  orbit_log "installing $IMAGE (~1 GB, one time)"
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  if [ -n "$ANDROID_CLI" ]; then
    "$ANDROID_CLI" sdk install "$IMAGE"
  else
    "$SDKMANAGER" "$IMAGE"
  fi
fi

if ! "$AVDMANAGER" list avd 2>/dev/null | grep -q "Name: $ORBIT_EMULATOR"; then
  orbit_log "creating AVD $ORBIT_EMULATOR"
  printf 'no\n' | "$AVDMANAGER" create avd -n "$ORBIT_EMULATOR" -k "$IMAGE" -d tv_720p
  CFG="$HOME/.android/avd/$ORBIT_EMULATOR.avd/config.ini"
  if [ -f "$CFG" ]; then
    sed -i 's/^hw.ramSize=.*/hw.ramSize=1024/' "$CFG" || true
    grep -q '^hw.ramSize=' "$CFG" || echo 'hw.ramSize=1024' >> "$CFG"
  fi
fi

if adb devices | grep -q '^emulator-.*device$'; then
  orbit_log "emulator already running"
  exit 0
fi

orbit_log "starting $ORBIT_EMULATOR (software GPU; headless)"
nohup "$ANDROID_HOME/emulator/emulator" -avd "$ORBIT_EMULATOR" \
  -no-snapshot -no-boot-anim -no-audio -memory 1024 \
  -gpu swiftshader_indirect -netdelay none -netspeed full \
  > artifacts/emulator.log 2>&1 &

adb wait-for-device
for _ in $(seq 1 180); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    orbit_log "emulator booted: $(adb devices | grep emulator | head -1)"
    exit 0
  fi
  sleep 2
done
orbit_log "emulator did not boot in time; see artifacts/emulator.log"
exit 1
