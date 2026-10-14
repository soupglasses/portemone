#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail
cd "$(dirname "$0")/.."

card_test_serial="${1:?Usage: scripts/test-card-lifecycle.sh emulator-SERIAL}"
[[ "$card_test_serial" == emulator-* ]] || {
    echo 'An emulator serial is required.' >&2
    exit 1
}
card_test_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$card_test_sdk" ]]; then
    card_test_sdk="$(sed -n 's/^sdk.dir=//p' local.properties)"
fi
card_test_adb="$card_test_sdk/platform-tools/adb"
[[ "$("$card_test_adb" -s "$card_test_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
    echo 'The selected device is not an emulator.' >&2
    exit 1
}
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:testDebugUnitTest :app:lintDebug --console=plain
"$card_test_adb" -s "$card_test_serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$card_test_adb" -s "$card_test_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p app/build/reports/card-lifecycle
card_test_report="app/build/reports/card-lifecycle/instrumentation.txt"
"$card_test_adb" -s "$card_test_serial" shell am instrument -w -r \
    -e class dev.finnes.portemone.LocalCardRepositoryTest,dev.finnes.portemone.PKPassLifecycleTest \
    dev.finnes.portemone.test/androidx.test.runner.AndroidJUnitRunner | tee "$card_test_report"
python3 - "$card_test_report" <<'PY'
import pathlib, re, sys
output = pathlib.Path(sys.argv[1]).read_text()
if not re.search(r'OK \([1-9]\d* tests?\)', output) or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output or 'INSTRUMENTATION_STATUS_CODE: -3' in output:
    raise SystemExit('Card lifecycle tests did not pass; inspect ' + sys.argv[1])
PY
