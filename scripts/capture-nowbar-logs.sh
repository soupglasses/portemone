#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

# Capture only Portemoné's integration diagnostics, not a device-wide notification dump.
# Usage: bash scripts/capture-nowbar-logs.sh [adb-device-serial]
set -euo pipefail
umask 077

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
adb_bin="${ADB:-}"
if [[ -z "$adb_bin" ]]; then
    adb_bin="$(command -v adb || true)"
fi
if [[ -z "$adb_bin" ]]; then
    adb_bin="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}/platform-tools/adb"
fi
if [[ ! -x "$adb_bin" ]]; then
    printf 'ADB not found. Set ADB to your SDK platform-tools/adb path.\n' >&2
    exit 1
fi

device_serial="${1:-${ANDROID_SERIAL:-}}"
if [[ -z "$device_serial" ]]; then
    device_list="$("$adb_bin" devices)"
    mapfile -t connected < <(awk 'NR > 1 && $2 == "device" { print $1 }' <<<"$device_list")
    if [[ ${#connected[@]} -ne 1 ]]; then
        printf '%s\n\n' "$device_list"
        printf 'Connect/authorize the Fold4, then run with its serial:\n  bash scripts/capture-nowbar-logs.sh DEVICE_SERIAL\n' >&2
        exit 1
    fi
    device_serial="${connected[0]}"
fi
device_adb=("$adb_bin" -s "$device_serial")
"${device_adb[@]}" get-state >/dev/null

mkdir -p -- "$project_dir/.diagnostics"
capture="$(mktemp "$project_dir/.diagnostics/nowbar-logs.XXXXXX")"
trap 'rm -f -- "$capture"' EXIT
{
    printf 'Portemoné Now Bar capture: %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    for property in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk ro.build.version.oneui; do
        value="$("${device_adb[@]}" shell getprop "$property")"
        printf '%s: %s\n' "$property" "${value//$'\r'/}"
    done
    printf '\n'
    "${device_adb[@]}" logcat -b main -d -v threadtime 'PortemoneNowBar:D' '*:S'
} >"$capture"
destination="$project_dir/.diagnostics/nowbar-logs.txt"
mv -- "$capture" "$destination"
printf 'Saved %s\n' "$destination"
if ! grep -q 'PortemoneNowBar' "$destination"; then
    printf 'No Now Bar diagnostics found. Select Samsung Now Bar in Portemoné, show a notification, and run again.\n'
fi
