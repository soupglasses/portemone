#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail
cd "$(dirname "$0")/.."

# Real provider tests replace mock providers and runtime permissions. Require an
# explicit emulator serial so a connected personal phone can never be selected.
location_test_serial="${1:?Usage: scripts/test-location-engines.sh emulator-SERIAL}"
[[ "$location_test_serial" == emulator-* ]] || {
    echo 'An emulator serial is required.' >&2
    exit 1
}
location_test_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$location_test_sdk" ]]; then
    location_test_sdk="$(sed -n 's/^sdk.dir=//p' local.properties)"
fi
location_test_adb="$location_test_sdk/platform-tools/adb"
[[ "$("$location_test_adb" -s "$location_test_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
    echo 'The selected device is not an emulator.' >&2
    exit 1
}
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
"$location_test_adb" -s "$location_test_serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$location_test_adb" -s "$location_test_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for location_test_permission in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION; do
    "$location_test_adb" -s "$location_test_serial" shell pm grant dev.finnes.portemone "android.permission.$location_test_permission"
done
if (($("$location_test_adb" -s "$location_test_serial" shell getprop ro.build.version.sdk | tr -d '\r') >= 33)); then
    "$location_test_adb" -s "$location_test_serial" shell pm grant dev.finnes.portemone android.permission.POST_NOTIFICATIONS
fi
mkdir -p app/build/reports/location-engines
location_test_report="app/build/reports/location-engines/instrumentation.txt"
"$location_test_adb" -s "$location_test_serial" shell am instrument -w -r \
    -e locationEngineTests true \
    -e class dev.finnes.portemone.LocationEngineBehaviorTest,dev.finnes.portemone.GeofenceLifecycleTest,dev.finnes.portemone.CardProximityTest,dev.finnes.portemone.NearbyRefreshTest,dev.finnes.portemone.FrameworkWithoutGoogleTest \
    dev.finnes.portemone.test/androidx.test.runner.AndroidJUnitRunner | tee "$location_test_report"
# adb/am can exit successfully even when JUnit fails.
python3 - "$location_test_report" <<'PY'
import pathlib, re, sys
output = pathlib.Path(sys.argv[1]).read_text()
if not re.search(r'OK \(\d+ tests?\)', output) or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output:
    raise SystemExit('Location behavior tests did not pass; inspect ' + sys.argv[1])
PY
