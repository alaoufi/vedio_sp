#!/usr/bin/env bash
set -euo pipefail

# Synthetic music only; no user media or library data enters the test APK.
mkdir -p app/src/androidTest/assets
if ! command -v ffmpeg >/dev/null; then
  sudo apt-get update -qq
  sudo apt-get install -y ffmpeg
fi
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:duration=164' \
  -ar 44100 -ac 2 -codec:a libmp3lame -y app/src/androidTest/assets/tone.mp3

test -e /dev/kvm
sudo chmod a+rw /dev/kvm
sdkmanager 'emulator' 'system-images;android-35;google_apis;x86_64' >/dev/null
export ANDROID_AVD_HOME="${RUNNER_TEMP:?}/slideshow-avds"
export ANDROID_USER_HOME="$RUNNER_TEMP/slideshow-android"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
printf 'no\n' | avdmanager create avd --force --name slideshow-test \
  --path "$ANDROID_AVD_HOME/slideshow-test.avd" \
  --package 'system-images;android-35;google_apis;x86_64'
# SDK tools and newer emulator versions can use different default user homes.
# Point both explicitly at the same AVD, including its discovery entry.
test -f "$ANDROID_AVD_HOME/slideshow-test.avd/config.ini"
printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-35\n' \
  "$ANDROID_AVD_HOME/slideshow-test.avd" > "$ANDROID_AVD_HOME/slideshow-test.ini"
"$ANDROID_HOME/emulator/emulator" -list-avds | grep -Fx slideshow-test
"$ANDROID_HOME/emulator/emulator" -avd slideshow-test -no-window -no-audio \
  -no-snapshot -no-boot-anim -no-metrics -gpu swiftshader_indirect -memory 2048 -cores 2 \
  > emulator.log 2>&1 &
emulator_pid=$!
trap 'adb emu kill >/dev/null 2>&1 || true; kill "$emulator_pid" 2>/dev/null || true' EXIT
sleep 2
if ! kill -0 "$emulator_pid" 2>/dev/null; then cat emulator.log; exit 1; fi
timeout 180 adb wait-for-device
booted=false
for _ in $(seq 1 90); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
    booted=true
    break
  fi
  sleep 2
done
if [ "$booted" != true ]; then cat emulator.log; exit 1; fi
adb shell input keyevent 82
./gradlew connectedDebugAndroidTest --stacktrace \
  -PversionCode="$1" -PversionName="1.0.$1" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.myvideolibrary.app.util.SlideshowMediaTest
