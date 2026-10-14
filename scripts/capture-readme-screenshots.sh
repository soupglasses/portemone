#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail
cd "$(dirname "$0")/.."

avd_name=Portemone_Screenshots
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[[ -n "$sdk" ]] || sdk=$(sed -n 's/^sdk.dir=//p' local.properties)
adb_bin="$sdk/platform-tools/adb"
emulator_bin="$sdk/emulator/emulator"
[[ -x "$adb_bin" && -x "$emulator_bin" ]] || {
    echo 'Android SDK platform-tools and emulator are required.' >&2
    exit 1
}

# Only this AVD is disposable: the script clears its app data before seeding cards.
serial=
while read -r candidate state _; do
    [[ "$candidate" == emulator-* && "$state" == device ]] || continue
    if [[ "$("$adb_bin" -s "$candidate" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')" == "$avd_name" ]]; then
        serial=$candidate
        break
    fi
done < <("$adb_bin" devices)

if [[ -z "$serial" ]]; then
    while read -r candidate state _; do
        [[ "$candidate" == emulator-* ]] || continue
        echo "Another emulator ($candidate) is running; wait until it is free before capturing." >&2
        exit 1
    done < <("$adb_bin" devices)
    if ! "$emulator_bin" -list-avds | grep -Fxq "$avd_name"; then
        echo "Create the dedicated $avd_name AVD first; see DEVELOPMENT.md." >&2
        exit 1
    fi
    "$emulator_bin" -avd "$avd_name" -no-snapshot -no-audio >/tmp/portemone-screenshot-emulator.log 2>&1 &
    emulator_pid=$!
    for ((attempt = 0; attempt < 120; attempt++)); do
        while read -r candidate state _; do
            [[ "$candidate" == emulator-* && "$state" == device ]] || continue
            if [[ "$("$adb_bin" -s "$candidate" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')" == "$avd_name" ]]; then
                serial=$candidate
                break
            fi
        done < <("$adb_bin" devices)
        [[ -n "$serial" ]] && break
        kill -0 "$emulator_pid" 2>/dev/null || {
            echo 'Screenshot emulator exited during startup.' >&2
            exit 1
        }
        sleep 2
    done
fi
[[ -n "$serial" ]] || {
    echo 'Screenshot emulator did not start.' >&2
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
    names=(wallet nearby-first qr code-128 pdf417)
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
echo 'Captured matching light and dark wallet, nearby, and three scan-format screenshots.'
