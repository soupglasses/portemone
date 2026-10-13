#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail
cd "$(dirname "$0")/.."

include_host_tests=false
if [[ "${1:-}" == --all ]]; then
    include_host_tests=true
    shift
fi
serial=${1:?An isolated API 33+ emulator serial is required, e.g. emulator-5554}
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
api=$("$adb_bin" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
if [[ ! "$api" =~ ^[0-9]+$ ]] || ((api < 33)); then
    echo 'The complete emulator suite requires API 33+ for notification delegation.' >&2
    exit 1
fi
export ANDROID_SERIAL="$serial"
if [[ "$include_host_tests" == true ]]; then
    mise run test
fi
# These suites change shared device state and must not run concurrently.
for suite in device helper location cards; do
    mise run "test:$suite" -- "$serial"
done
