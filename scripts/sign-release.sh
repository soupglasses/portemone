#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail

: "${ANDROID_HOME:?Android SDK path is required}"
: "${ANDROID_KEYSTORE_BASE64:?Base64-encoded release keystore is required}"
: "${ANDROID_KEYSTORE_PASSWORD:?Keystore password is required}"
: "${ANDROID_KEY_ALIAS:?Signing alias is required}"
: "${ANDROID_KEY_PASSWORD:?Key password is required}"

app_apk=${1:?Main unsigned APK is required}
helper_apk=${2:?Helper unsigned APK is required}
output=${3:?Output directory is required}
apksigner="$ANDROID_HOME/build-tools/36.0.0/apksigner"
certificate_pin=$(sed '/^#/d' "$(dirname "$0")/../signing-certificate.sha256" |
    tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')
if [[ ! "$certificate_pin" =~ ^[0-9a-f]{64}$ ]]; then
    echo 'Set the public release certificate fingerprint in signing-certificate.sha256.' >&2
    exit 1
fi

umask 077
signing_dir=$(mktemp -d)
trap 'rm -rf "$signing_dir"' EXIT
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode >"$signing_dir/release.p12"
unset ANDROID_KEYSTORE_BASE64
mkdir -p "$output"

sign_apk() {
    "$apksigner" sign \
        --ks "$signing_dir/release.p12" \
        --ks-key-alias "$ANDROID_KEY_ALIAS" \
        --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
        --key-pass env:ANDROID_KEY_PASSWORD \
        --v4-signing-enabled false \
        --out "$2" "$1"
    verification=$("$apksigner" verify --verbose --print-certs "$2")
    printf '%s\n' "$verification"
    actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$verification")
    if [[ "$actual" != "$certificate_pin" ]]; then
        echo "Signing certificate mismatch for $(basename "$2")." >&2
        exit 1
    fi
}

# Both packages must share the signing identity for notification delegation.
sign_apk "$app_apk" "$output/portemone.apk"
sign_apk "$helper_apk" "$output/portemone-notification-companion.apk"
