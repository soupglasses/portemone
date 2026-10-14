#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail
cd "$(dirname "$0")/.."

if (($# > 1)); then
    echo 'Usage: scripts/capture-readme-screenshots.sh [AVD_NAME]' >&2
    exit 2
fi
avd_name=${1:-${PORTEMONE_SCREENSHOT_AVD:-}}
if [[ -z "$avd_name" && -t 0 ]]; then
    read -r -p 'Running AVD name: ' avd_name
fi
[[ -n "$avd_name" ]] || {
    echo 'Pass the running AVD name as an argument or set PORTEMONE_SCREENSHOT_AVD.' >&2
    exit 2
}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[[ -n "$sdk" ]] || sdk=$(sed -n 's/^sdk.dir=//p' local.properties)
adb_bin="$sdk/platform-tools/adb"
[[ -x "$adb_bin" ]] || {
    echo 'Android SDK platform-tools are required.' >&2
    exit 1
}

# The capture resets only Portemoné's app data on the selected emulator.
serial=
while read -r candidate state _; do
    [[ "$candidate" == emulator-* && "$state" == device ]] || continue
    if [[ "$("$adb_bin" -s "$candidate" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')" == "$avd_name" ]]; then
        serial=$candidate
        break
    fi
done < <("$adb_bin" devices)

[[ -n "$serial" ]] || {
    echo "Start the visible $avd_name emulator before capturing screenshots." >&2
    exit 1
}
while read -r candidate state _; do
    [[ "$candidate" == emulator-* && "$candidate" != "$serial" ]] || continue
    echo "Another emulator ($candidate) is running; wait until it is free before capturing." >&2
    exit 1
done < <("$adb_bin" devices)
"$adb_bin" -s "$serial" wait-for-device
for ((attempt = 0; attempt < 120; attempt++)); do
    [[ "$("$adb_bin" -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] && break
    sleep 2
done
[[ "$("$adb_bin" -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || {
    echo 'Screenshot emulator did not finish booting.' >&2
    exit 1
}
[[ "$("$adb_bin" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
    echo 'Refusing to capture on a physical device.' >&2
    exit 1
}

export ANDROID_SERIAL="$serial"
test_log=$(mktemp)
capture_dir=$(mktemp -d)
cleanup() {
    "$adb_bin" -s "$serial" shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1 || true
    "$adb_bin" -s "$serial" shell cmd uimode night auto >/dev/null 2>&1 || true
    rm -f "$test_log"
    rm -rf "$capture_dir"
}
trap cleanup EXIT
"$adb_bin" -s "$serial" shell wm dismiss-keyguard
"$adb_bin" -s "$serial" shell settings put system screen_off_timeout 1800000
"$adb_bin" -s "$serial" shell svc power stayon true
"$adb_bin" -s "$serial" shell input keyevent KEYCODE_WAKEUP
"$adb_bin" -s "$serial" shell wm size 1080x2400
"$adb_bin" -s "$serial" shell wm density 420
"$adb_bin" -s "$serial" shell settings put global sysui_demo_allowed 1
"$adb_bin" -s "$serial" shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
"$adb_bin" -s "$serial" shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
"$adb_bin" -s "$serial" shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
"$adb_bin" -s "$serial" shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e mobile show -e level 4 >/dev/null
"$adb_bin" -s "$serial" shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

./gradlew --no-daemon --console=plain --max-workers=2 \
    :app:assembleDebug :app:assembleDebugAndroidTest
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for theme in light dark; do
    if [[ "$theme" == light ]]; then night=no; else night=yes; fi
    "$adb_bin" -s "$serial" shell cmd uimode night "$night"
    "$adb_bin" -s "$serial" shell pm clear dev.finnes.portemone
    "$adb_bin" -s "$serial" shell pm grant dev.finnes.portemone android.permission.ACCESS_FINE_LOCATION
    "$adb_bin" -s "$serial" shell pm grant dev.finnes.portemone android.permission.ACCESS_BACKGROUND_LOCATION
    "$adb_bin" -s "$serial" emu geo fix 12.5683 55.6761
    "$adb_bin" -s "$serial" shell am instrument -w \
        -e class dev.finnes.portemone.ReadmeScreenshotTest \
        -e screenshotTheme "$theme" \
        dev.finnes.portemone.test/androidx.test.runner.AndroidJUnitRunner | tee "$test_log"
    grep -Fq 'OK (1 test)' "$test_log" || {
        echo "Screenshot instrumentation did not pass for $theme mode." >&2
        exit 1
    }
    mkdir -p "$capture_dir/$theme"
    "$adb_bin" -s "$serial" pull \
        "/sdcard/Android/data/dev.finnes.portemone/files/readme-screenshots/$theme/." \
        "$capture_dir/$theme/"
done

for theme in light dark; do
    mkdir -p "docs/assets/screenshots/$theme"
    names=(wallet nearby-first card-details)
    for name in "${names[@]}"; do
        [[ -s "$capture_dir/$theme/$name.png" ]] || {
            echo "Missing $theme/$name.png from screenshot run." >&2
            exit 1
        }
        cp "$capture_dir/$theme/$name.png" "docs/assets/screenshots/$theme/$name.png"
        printf 'SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>\nSPDX-License-Identifier: EUPL-1.2\n' \
            >"docs/assets/screenshots/$theme/$name.png.license"
    done
done
mkdir -p docs/assets/screenshots/expanded
for name in qr code-128 pdf417; do
    [[ -s "$capture_dir/light/$name.png" ]] || {
        echo "Missing expanded/$name.png from screenshot run." >&2
        exit 1
    }
    cp "$capture_dir/light/$name.png" "docs/assets/screenshots/expanded/$name.png"
    printf 'SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>\nSPDX-License-Identifier: EUPL-1.2\n' \
        >"docs/assets/screenshots/expanded/$name.png.license"
done
echo 'Captured light and dark wallet, nearby, card details, and shared expanded-code screenshots.'
