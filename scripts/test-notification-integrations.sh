#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail
cd "$(dirname "$0")/.."

notification_test_serial="${1:?Usage: scripts/test-notification-integrations.sh emulator-SERIAL}"
[[ "$notification_test_serial" == emulator-* ]] || {
    echo 'An isolated emulator is required.' >&2
    exit 1
}
notification_test_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$notification_test_sdk" ]] || notification_test_sdk="$(sed -n 's/^sdk.dir=//p' local.properties)"
notification_test_adb="$notification_test_sdk/platform-tools/adb"
adb_test() { "$notification_test_adb" -s "$notification_test_serial" "$@"; }
[[ "$(adb_test shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || exit 1
(($(adb_test shell getprop ro.build.version.sdk | tr -d '\r') >= 33)) || {
    echo 'Notification delegation tests require Android 13+.' >&2
    exit 1
}
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1280m \
    -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug \
    :notification-companion:assembleDebug :notification-companion:assembleDebugAndroidTest \
    :notification-companion:lintDebug --console=plain
for notification_test_apk in app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    notification-companion/build/outputs/apk/debug/notification-companion-debug.apk \
    notification-companion/build/outputs/apk/androidTest/debug/notification-companion-debug-androidTest.apk; do
    adb_test install -r "$notification_test_apk"
done
notification_test_main=dev.finnes.portemone
notification_test_helper=dev.finnes.portemone.notificationcompanion
notification_test_report_dir=app/build/reports/notification-integrations
mkdir -p "$notification_test_report_dir"
run_test() {
    local report="$notification_test_report_dir/$1.txt"
    shift
    adb_test shell am instrument -w -r "$@" | tee "$report"
    python3 - "$report" <<'PY'
import pathlib, re, sys
output = pathlib.Path(sys.argv[1]).read_text()
if not re.search(r'OK \(\d+ tests?\)', output) or 'FAILURES!!!' in output or 'INSTRUMENTATION_FAILED' in output or 'INSTRUMENTATION_STATUS_CODE: -3' in output:
    raise SystemExit('Notification behavior tests failed: ' + sys.argv[1])
PY
}
helper_test() {
    run_test "$1" -e notificationIntegrationTests true -e helperDelegate "$2" \
        -e class "dev.finnes.portemone.notificationcompanion.NotificationDelegationTest${3:-}" \
        "$notification_test_helper.test/androidx.test.runner.AndroidJUnitRunner"
}
main_test() {
    run_test "$1" -e companionNotificationTest "$1" -e class dev.finnes.portemone.CompanionNotificationDeviceTest \
        "$notification_test_main.test/androidx.test.runner.AndroidJUnitRunner"
}
cleanup() {
    adb_test shell pm enable "$notification_test_helper" >/dev/null
    adb_test shell pm grant "$notification_test_main" android.permission.POST_NOTIFICATIONS
    adb_test shell pm grant "$notification_test_helper" android.permission.POST_NOTIFICATIONS
    # Restore the original consent instead of leaving full cards disconnected after testing.
    helper_test cleanup "$notification_test_original_delegate" '#explicitConsentEnablesAndRevokesTheActualDelegate' >/dev/null
}
notification_test_original_delegate=false
trap cleanup EXIT
adb_test shell pm enable "$notification_test_helper"
adb_test shell pm grant "$notification_test_main" android.permission.POST_NOTIFICATIONS
adb_test shell pm grant "$notification_test_helper" android.permission.POST_NOTIFICATIONS
helper_test original-consent restore '#reportConnection'
if rg -q 'helperConnected=true' "$notification_test_report_dir/original-consent.txt"; then
    notification_test_original_delegate=true
fi
helper_test helper-setup true
main_test lifecycle
main_test render
adb_test shell pm revoke "$notification_test_helper" android.permission.POST_NOTIFICATIONS
main_test blocked
helper_test revoke false '#explicitConsentEnablesAndRevokesTheActualDelegate'
main_test blocked-revoked
adb_test shell pm grant "$notification_test_helper" android.permission.POST_NOTIFICATIONS
main_test fallback
helper_test reconnect true '#explicitConsentEnablesAndRevokesTheActualDelegate'
adb_test shell pm revoke "$notification_test_main" android.permission.POST_NOTIFICATIONS
main_test main-blocked
adb_test shell pm grant "$notification_test_main" android.permission.POST_NOTIFICATIONS
adb_test shell pm disable-user --user 0 "$notification_test_helper"
main_test fallback-disabled
adb_test shell pm enable "$notification_test_helper"
run_test samsung-and-rendering -e class dev.finnes.portemone.CardPrototypeTest \
    "$notification_test_main.test/androidx.test.runner.AndroidJUnitRunner"

run_test presentation-settings -e class dev.finnes.portemone.NotificationPresentationUiTest \
    "$notification_test_main.test/androidx.test.runner.AndroidJUnitRunner"
