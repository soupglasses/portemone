#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANDROID_HOME:?Android SDK path is required}"
: "${ANDROID_KEYSTORE_BASE64:?Base64-encoded release keystore is required}"
: "${ANDROID_KEYSTORE_PASSWORD:?Keystore password is required}"
: "${ANDROID_KEY_ALIAS:?Signing alias is required}"
: "${ANDROID_KEY_PASSWORD:?Key password is required}"

# Signing credentials are needed only by apksigner, not Gradle or its cache.
env -u ANDROID_KEYSTORE_BASE64 -u ANDROID_KEYSTORE_PASSWORD \
    -u ANDROID_KEY_ALIAS -u ANDROID_KEY_PASSWORD \
    ./gradlew --no-daemon --console=plain --stacktrace --max-workers=2 \
    :app:assembleRelease :app:lintRelease \
    :notification-companion:assembleRelease :notification-companion:lintRelease \
    cyclonedxBom

python3 scripts/check-apk-pages.py \
    app/build/outputs/apk/release/app-release-unsigned.apk \
    notification-companion/build/outputs/apk/release/notification-companion-release-unsigned.apk

# Stage the complete release before replacing any previous local output.
staging=$(mktemp -d "$PWD/build/release.XXXXXX")
trap 'rm -rf "$staging"' EXIT
bash scripts/sign-release.sh \
    app/build/outputs/apk/release/app-release-unsigned.apk \
    notification-companion/build/outputs/apk/release/notification-companion-release-unsigned.apk \
    "$staging"
cp app/build/reports/cyclonedx-direct/bom.json "$staging/portemone.cdx.json"
cp notification-companion/build/reports/cyclonedx-direct/bom.json "$staging/portemone-notification-companion.cdx.json"
(
    cd "$staging"
    sha256sum portemone.apk portemone-notification-companion.apk portemone.cdx.json portemone-notification-companion.cdx.json >SHA256SUMS
)
rm -rf build/release
mv "$staging" build/release
