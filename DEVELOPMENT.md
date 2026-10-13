<!--
SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
SPDX-License-Identifier: EUPL-1.2
-->

# Development

## Build

Install [mise](https://mise.jdx.dev/) and Android SDK platform 37 / build tools
36.0.0. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`, then:

```sh
mise trust
mise install
mise run build
mise run install
```

The optional `notification-companion` uses legacy notification layouts across
phone brands. It has been tested on a Pixel emulator and Xiaomi; see the
[tested devices](README.md#tested-devices) for results and limitations.
Its Android package ID is `dev.finnes.portemone.notificationcompanion`.
Install with `mise run install:helper`, then enable it through
Portemoné's notification settings. It has no launcher entry; Samsung does not need it.

## Saving cards and updating nearby notifications

Card saves commit locally to SQLite. The same transaction records pending presentation
work in a coalescing outbox, including deletion tombstones. Save does not acquire the
location coordinator's mutex or wait for location acquisition, geofence registration,
or barcode rendering. It cancels the edited card's old notification through the shared
integration boundary and schedules reconciliation with WorkManager.

The worker reads the current database state. It checks
changed circles against a cached fix only; missing, stale or uncertain fixes cannot
establish presence. Unchanged circles keep their existing presence. A saved region
anchor can select the bounded geofence set but cannot establish presence. Normal card
and location-label edits do not require location work. Explicit Nearby refresh still
requests a fresh fix. Background execution can be delayed by Android.

Pending work survives process death and is scheduled again on application startup.
Acknowledgement matches an entry's token, so an older attempt cannot consume a newer
edit. Registration receipts describe the exact boundaries sent to the provider. Failed work can be retried. A card can save successfully even if its notification
cannot update.
This follows Android's [local source of truth](https://developer.android.com/topic/architecture/data-layer/offline-first)
and [persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent)
guidance, applying the [transactional outbox pattern](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
to local system effects.

## CI and testing

CI builds both apps, lints debug and release variants, runs JVM and device tests
(API 31 and 36), and generates a dependency SBOM.
JUnit counts appear on the build and device job checks, with detailed results in
each job summary. Reports are not uploaded as artifacts.
Run `mise run lint` locally, or `mise run lint:android` for Android Lint only.
`mise run lint:repo` checks workflows (actionlint), shell (ShellCheck/shfmt), and
Python (Ruff); `mise run format` formats shell and Python scripts.
Generate the SBOM locally with `mise run sbom`;
output: `build/reports/cyclonedx/bom.json`.

Run `mise run test` for JVM and release-script tests (`ANDROID_HOME` required).
Run `mise run test:all -- emulator-5554` for the complete suite, or
`mise run test:emulator -- emulator-5554` for all emulator tests. These require an
isolated API 33+ emulator and run device suites sequentially. Individual suites:

```sh
mise run test:device -- emulator-5554
mise run test:helper -- emulator-5554  # API 33+, companion delegation and fallback
mise run test:location -- emulator-5554
mise run test:cards -- emulator-5554
```

Use `mise tasks` to list commands. CI uses the same build, lint and test tasks.

Run `mise run lint:reuse` to check source licensing metadata; CI runs the same
check, and `mise run lint` includes it. Add SPDX headers to new
files and preserve imported licenses. Use `.license` sidecars for binary files;
see [third-party notices](THIRD_PARTY_NOTICES.md) for provenance and exceptions.

Instrumentation CI discovers all unannotated tests automatically. Its device runner
disables the baseline lock screen and keeps the emulator awake between tests.
Privacy tests enable and restore their own swipe or PIN lock.
`@RequiresDeviceSetup` marks tests whose device state is owned by a dedicated runner
(location/provider scenarios, companion delegation, system-settings scenarios or online maps).
The annotation must explain the required setup. CI rejects skipped, failed or missing ordinary test results. Do not use assumptions
to skip these tests. New barcode,
appearance, location-editor and lock-screen privacy regressions run by default.

See [device testing](docs/validation.md) and [planned work](https://github.com/soupglasses/portemone/issues).

## App text and translations

Keep app-owned UI text in each module's `res/values/strings.xml`, including
accessibility labels, notifications and error messages. English is the default.
Use sentence case, short action labels and error messages that explain how to
recover. Keep permission and import warnings specific.

Use `stringResource` in Compose. For callbacks, capture `LocalResources.current`
and call `getString`; outside Compose, use the current context's resources.
Use numbered format arguments and `plurals` for counts. Translate whole messages
so each language can choose its word order. Never localize membership codes,
storage keys, URLs, issuer-provided text or protocol identifiers. Format dates for
display while keeping stored timestamps unchanged.

Add translations in `res/values-<language>/` (or BCP 47 resource directories).
`resources.properties` declares English as the fallback; Gradle generates Android's
per-app language configuration from the available translations. Only add languages
with reviewed app translations. Android 13+ exposes the language choice in system
settings; older versions use the device language.

Debug builds enable Android's `en-XA` and `ar-XB` pseudolocales. Run
`LocalizedTextTest` and inspect the card editor, import review and notification
setup with long text, a large font and right-to-left layout. Check TalkBack labels
and ensure membership values keep their original characters and order. Saved pass
warnings use stable names; their old English values remain readable and translate
when displayed.

See Android's [string resources](https://developer.android.com/guide/topics/resources/string-resource),
[Compose resources](https://developer.android.com/develop/ui/compose/resources) and
[per-app languages](https://developer.android.com/guide/topics/resources/app-languages).

## Icon assets

Android uses the vector resources in each app's `src/main/res/drawable/` directory;
notification posts reference `ic_card.xml`. The geometry source is
`tools/generate_icons.py`. Run `python3 tools/generate_icons.py` to regenerate the
main app's icons; copy changed launcher vectors to the helper's drawable directory
when updating the shared branding. The README logo belongs in `docs/assets/`.
`docs/assets/notification-icon-native.png` preserves the original notification
preview. The generator also writes `docs/assets/notification-icon-native.svg`
from the same geometry as `ic_card.xml`, including its clipping and transforms.
The white artwork has a transparent background. To render a fresh preview with
librsvg without overwriting the preserved original:

```sh
rsvg-convert -o /tmp/notification-icon.png docs/assets/notification-icon-native.svg
```

Rasterization can differ between renderers; the Android vector is the shipped
asset. Design comparisons are not required to build the apps.

## Commits and tags

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)
with clear subjects, such as `fix: preserve card selection after refresh`.

Sign all commits and release tags with an SSH or GPG key registered to your
GitHub profile. They must show as **Verified** on GitHub. Follow
[GitHub's signing setup](https://docs.github.com/en/authentication/managing-commit-signature-verification/telling-git-about-your-signing-key).
APK signing uses the separate Android key described below.

## Releases

Create a signed `vMAJOR.MINOR.PATCH` tag to set the version of both apps. For example,
`v1.2.3` produces version name `1.2.3` and Android version code `10203`, using
`major * 10000 + minor * 100 + patch`. Minor and patch must be in `0..99`; the
result must be in Android's `1..2100000000` range. CI rejects versions below an
existing release and multiple version tags on the same commit. Retrying the same
tag is allowed; a changed artifact uploaded to Play needs a new version.

Untagged or modified builds use the highest reachable stable tag with a `-dev`
suffix, or `0.0.0-dev` (code `1`) before the first tag or without Git metadata.
Use a full checkout with tags for consistent versions. Prerelease tags are not
supported by this stable-release scheme.

Settings shows the version, a 12-character Git hash and the commit date for release
builds from a clean checkout at the matching version tag. Other builds show the hash
and commit date, with a `modified` marker when tracked or untracked source changes
are present. The date is the commit's committer date, not the build date. Source
archives without Git metadata show `Git revision unavailable`. A checkout with a
`.git` entry must provide a valid revision; Git failures stop the build and show
Git's error in Android Studio's Build output. This prevents a broken checkout
from silently producing an unidentified APK. Gradle tracks these
Git reads as configuration-cache inputs so new commits and local changes refresh
the displayed identity.

Keep a persistent Android signing key in your secret manager, with a secure backup.
Store the base64-encoded keystore as `ANDROID_KEYSTORE_BASE64`; local builds and CI
use the same value. The signing script decodes it to a private temporary file and
removes it on exit. GitHub attestations are additional provenance, not APK signatures.

For local release builds, copy `.env.example` to the ignored `.env` with owner-only
permissions if it does not exist, then fill in the existing signing credentials:

```sh
test -e .env || install -m 600 .env.example .env
# Fill in .env before running:
mise run release
```

mise loads `.env` locally and marks its values for redaction; CI uses GitHub Secrets.
Keep passwords single-quoted as shown in `.env.example` to prevent variable expansion.

Outputs in `build/release/`: `portemone.apk`, `portemone-notification-companion.apk`, their SBOMs
and checksums. Both APKs are signed and verified using the same key.

The expected public signing identity is pinned in `signing-certificate.sha256`.
Use the existing release keystore for official updates; both APKs must match the
pin. Forks need their own signing identity and fingerprint. See
[Android's app-signing guide](https://developer.android.com/studio/publish/app-signing)
for key creation and Play App Signing.

### GitHub releases

The release workflow uses the GitHub environment `release`, restricted to `v*`
tags. Its secrets are `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.
After main CI passes, push the matching signed tag, such as `v1.0.0`. CI runs the same script,
adds GitHub attestations and publishes once all assets are uploaded. Failed uploads
leave a draft that can be retried. Verify a downloaded APK with
`gh attestation verify portemone.apk --repo soupglasses/portemone`.

### 16 KB page-size compatibility

After building both release APKs, run `mise run check:pages`. CI and release
packaging reject incompatible 64-bit ELF load segments or unaligned uncompressed
native ZIP entries. Run `mise run test:pages` when changing the checker.

Alignment checks do not establish runtime compatibility. On an isolated 16 KB
emulator, verify `adb -s SERIAL shell getconf PAGE_SIZE` prints `16384`, then test
native map rendering and lock/unlock recovery with `MapResumeTest`. Also exercise
card rendering. Repeat when native dependencies change. Check App Bundle packaging
with `bundletool dump config --bundle=app-release.aab`; it must request
`PAGE_ALIGNMENT_16K`. Follow [Android's page-size verification guidance](https://developer.android.com/guide/practices/page-sizes)
for bundle-generated APKs and physical-device testing.
