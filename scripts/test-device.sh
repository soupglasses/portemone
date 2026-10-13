#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail
cd "$(dirname "$0")/.."

serial=${1:?Usage: mise run test:device -- emulator-SERIAL}
[[ "$serial" == emulator-* ]] || {
    echo 'An isolated emulator is required.' >&2
    exit 1
}
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk" ]] || sdk=$(sed -n 's/^sdk.dir=//p' local.properties)
adb_bin="$sdk/platform-tools/adb"
[[ "$("$adb_bin" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
    echo 'The selected device is not an emulator.' >&2
    exit 1
}
# Ordinary UI tests start unlocked; privacy tests enable and restore their own lock.
# Keep the emulator awake between tests, while allowing explicit sleep/wake tests.
"$adb_bin" -s "$serial" shell locksettings set-disabled true
"$adb_bin" -s "$serial" shell settings put system screen_off_timeout 1800000
"$adb_bin" -s "$serial" shell svc power stayon true
"$adb_bin" -s "$serial" shell input keyevent KEYCODE_WAKEUP
for ((attempt = 0; attempt < 20; attempt++)); do
    "$adb_bin" -s "$serial" shell wm dismiss-keyguard
    "$adb_bin" -s "$serial" shell input keyevent KEYCODE_MENU
    if "$adb_bin" -s "$serial" shell dumpsys window policy | grep 'mIsShowing=false' >/dev/null; then
        break
    fi
    sleep 1
done
"$adb_bin" -s "$serial" shell dumpsys window policy | grep 'mIsShowing=false' >/dev/null || {
    echo 'The emulator keyguard did not dismiss.' >&2
    exit 1
}
# Keyguard dismissal must exercise real system transitions, not zero-duration animations.
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$adb_bin" -s "$serial" shell settings put global "$scale" 1
done
# Gradle and adb must target the same explicitly selected emulator.
export ANDROID_SERIAL="$serial"
mise run build:device
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
api=$("$adb_bin" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
if ((api >= 33)); then
    "$adb_bin" -s "$serial" shell pm grant dev.finnes.portemone android.permission.POST_NOTIFICATIONS
fi
for permission in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION; do
    "$adb_bin" -s "$serial" shell pm grant dev.finnes.portemone "android.permission.$permission"
done
if [[ "${CI:-}" == true ]]; then
    # Keep the last started test visible even if the job ends before reports upload.
    "$adb_bin" -s "$serial" logcat -v brief -T 1 'TestRunner:I' '*:S' &
    test_logcat_pid=$!
    cleanup_test_logcat() {
        kill "$test_logcat_pid" 2>/dev/null || true
        wait "$test_logcat_pid" 2>/dev/null || true
    }
    trap cleanup_test_logcat EXIT
fi
./gradlew --no-daemon --console=plain --stacktrace --max-workers=2 \
    :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notAnnotation=dev.finnes.portemone.RequiresDeviceSetup
python3 scripts/check-device-test-results.py app/build/outputs/androidTest-results/connected
