#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail
cd "$(dirname "$0")/../build/release"

tag=${1:?Release tag is required}
: "${GITHUB_REPOSITORY:?GitHub repository is required}"

releases=$(gh api "repos/$GITHUB_REPOSITORY/releases" --paginate --slurp)
existing=$(jq -r --arg tag "$tag" \
    '[.[][] | select(.tag_name == $tag)] | first | if . == null then "missing" else (.draft | tostring) end' \
    <<<"$releases")
if [[ "$existing" == missing ]]; then
    gh release create "$tag" --draft --verify-tag \
        --title "$tag" --generate-notes --fail-on-no-commits
elif [[ "$existing" != true ]]; then
    echo '::error::This version is already published; release a new version instead.'
    exit 1
fi

# Obtainium must only see a release after all of its assets are uploaded.
gh release upload "$tag" --clobber \
    portemone.apk portemone-notification-companion.apk \
    portemone.cdx.json portemone-notification-companion.cdx.json SHA256SUMS
gh release edit "$tag" --draft=false
