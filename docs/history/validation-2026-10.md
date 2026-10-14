<!--
SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
SPDX-License-Identifier: EUPL-1.2
-->

# Validation history: October 2026

Archived development reports. These describe the implementation and individual runs
at the time, including superseded behavior and unresolved findings later corrected.
Use [current validation guidance](../validation.md) for procedures and limitations.
Provider runs recorded before the scale-test change used 80 store fences; the later
99-store scale checks are JVM simulations, not provider delivery measurements.

Test counts describe individual historical runs. They do not report a current full-suite result. Open work and known failures are tracked in [GitHub issues](https://github.com/soupglasses/portemone/issues).

Check that nearby cards are ready to scan, keep their saved store radii, and disappear after leaving. Verify Samsung full-card/Now Bar presentation and exact black-on-white codes. Background geofence delays are acceptable; opening the app and refreshing should recover promptly.

## Recorded baseline

- Implemented: compact card list, nearby carousel, card detail/code view, editable store circles persisted in SQLite, local rolling-80 geofencing through Google Play services or Android Framework, one-shot foreground refresh, notification health, and redacted shareable diagnostics.
- Memberships and store edits persist in SQLite. Fresh wallets start empty. The old hardcoded catalog was subsequently removed; see Release privacy and regression discovery below for migration behavior.
- Samsung full-card notifications work on the tested Fold4 / One UI 8. Preserve this separately from Pixel experiments.
- Notification backends now share posting, fallback and cancellation in `app/src/main/java/dev/finnes/portemone/notifications/integrations/`. Samsung remains built in; `notification-companion/` is a separately installed companion. Portemoné still targets API 36; only the companion targets API 30. The optional integration currently requires Android 13+ so helper notification permission can be measured.
- Verified for the optional companion: main/helper builds and lint; 16 local unit tests; delegated lifecycle, blocked-helper/main permission, revoked-delegation fallback, and helper-health UI tests on the API 37 emulator; QR decoding from the secure lock screen. System-owned corners remove the pale wedges in the inspected expanded group. This does not establish support on physical Pixels or other manufacturers; swipe/open animations still need verification.
- Existing instrumented suites cover refresh, geofence lifecycle, maps, navigation, storage, health, diagnostics, and rendering. Their presence is not evidence that the complete release/device matrix has passed.
- One-off Pixel experiments are archived in `research/pixel-notifications/`, outside app build sources. Keep future research isolated; promote only measured, regression-tested integrations into the app.

## Notification integration evidence

- Card appearance is in Settings → Notifications with Automatic and Standard choices. Automatic uses built-in Samsung support or the connected helper; explicit existing opt-outs migrate to Standard. Helper setup and preview are available here, without a second Developer tools switch. Reopening Portemoné reconciles nearby notifications after helper setup.
- Three API 36 emulator tests verified the shared appearance setting, recreation, preference migration, and removal of the duplicate Developer tools switch. Actual expanded/lock-screen presentation still needs separate visual verification.

- Native Android, Samsung full-card, and optional companion backends share a posting/cancellation boundary. Keep Samsung's existing protocol inside its adapter and distinguish blocked from unavailable results.
- Each notification backend reports its readiness (ready, blocked, unavailable) to the shared routing code. A blocked Pixel helper remains blocked even after delegation is revoked.
- Verify helper opt-in/out, delegated nearby-card reconciliation and removal, revoked delegation, disabled-helper fallback, main/helper notification permission blocks, and blocked-plus-revoked state on the API 37 emulator. Cleanup remains under each notification’s owning package.
  - Research (2026-10-05, not device-verified): test a separate explicit group and matching summary for each helper card. A group key alone is insufficient: Android's delayed grouping handles children without summaries, and can also aggregate sparse/singleton groups when enabled. This is a candidate for a small nearby set, not a guaranteed opt-out. Compare with one stable notification containing the selected card and switch actions; its existing equal-height compact/expanded RemoteViews can retain the code without relying on expansion. Public Live Updates are expanded and uncollapsible, but prohibit custom RemoteViews and cannot preserve this card renderer. Verify delayed state, lock/unlock, updates and summary cleanup on EXIT before adopting either approach. Sources: [grouping API](https://developer.android.com/develop/ui/views/notifications/group), [Android 16 QPR2 grouping implementation](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/notification/GroupHelper.java), [Live Updates requirements](https://developer.android.com/develop/ui/views/notifications/live-update).

## Card lifecycle evidence

- Cards support local add/edit, archive/restore, confirmed deletion, distinct labels, string barcode values, optional descriptions, and barcode validation. Card editing now includes a full color picker (color plane, hue/saturation/brightness sliders and exact hex input); Description appears in full card details; scanning and card identification use the code value, while imported alternative text is retained in details. Fresh wallets start empty. Legacy location-only databases now recover plain cards and preserve circles without bundling membership values.
- Each opened card has Edit, Archive/Restore, and More for export/deletion. The add/import "+" appears beside Cards/Other cards in place of the count. The home automatic-cards status banner has been removed. Add/import launched from home returns to the list when finished. The section add path, inline edit/lifecycle, and Settings setup path passed in 14 API 37 emulator tests with the storage/import suite.
- The standalone PKPass importer produces normalized cards, fields, source metadata and suggested locations. It supports Android Open/Share and document selection, all five pass styles, QR/CODE_128/PDF417/Aztec, cards without codes, colors, localized strings/artwork, arbitrary fields, relevant dates, expiration and voided status. It stores the complete original file in SQLite for export.
- Import checks exact manifest coverage/SHA-1 hashes and detached PKCS#7 signatures, certificate validity, signer/pass identity and the Apple Pass Type ID extension. Certificate chains are checked offline against pinned, bundled Apple roots. Unsigned, altered, invalid or untrusted files require the explicit "Accept risk and import" action. Structural/size/encoding failures cannot be overridden.
- Imported updates keep the local card ID, archived status and saved locations, with an option to replace locations. Card, location and original-package writes are atomic. The importer enforces the ten-location limit and cascade deletion, keeps resource names stable across APK versions, and rejects stale edits.
- Verification covered version 3 migration, cascade cleanup, persistent reopen, transactional package replacement, source identity, stale edits, the ten-location cap, Open/Share intent resolution and review, unsigned override, recreation, cancellation, archive/restore/delete, and PDF417/Aztec pixel-size decoding: all 13 lifecycle/storage instrumentation tests passed on a disposable API 37 emulator.
- PKPass description and additional altText are combined into the editable card Description during import. Full card Details shows Description alongside locations. There is no separate caption input; scanning and identification use the actual code. All six PKPass lifecycle tests passed on the API 37 emulator, including color selection, description editing, Open/Share review and regenerated barcode decoding; all 37 JVM tests, debug/instrumentation builds and debug lint passed.
- Gradle debug/release and instrumentation APK builds, main/helper debug lint, and 37 JVM tests passed (including 11 PKPass normalization/security tests). Main lint reports zero errors and 88 warnings. Bouncy Castle is pinned to 1.86; bundled Apple roots are present in the optimized release APK.
- After the renderer changes, helper setup/delegation, all seven permission/routing modes, ten Samsung protocol/rendering/widget tests, and three presentation settings tests passed on the API 37 emulator. Legacy layout tests explicitly cover QR/CODE_128; the lifecycle suite separately verifies all imported barcode formats and code-free notification/widget behavior. Physical Samsung verification remains pending.

### Add-card experience

User acceptance of the add-card flow is tracked in [issue #10](https://github.com/soupglasses/portemone/issues/10).

As of 2026-10-13, manual add/edit has file-based logo selection, replacement/removal, a live card preview, and five AndroidX Palette-based color suggestions beneath it, with labeled tonal variations for sparse logos. Manual hex/color selection remains available. Selecting a color applies subtle shading to the preview, compact card and full notification face; existing card colors and legacy shading remain unchanged until a new color is selected. Imported logos have a contrasting backing to keep them visible against matching card colors. Logos are normalized to at most 512 pixels per edge and copied into card storage. Draft image bytes stay outside saved-state Bundles. Brand lookup, installed-app icons, visual format examples and scanning remain pending. QR, CODE_128, PDF417 and Aztec rendering already existed.

Verification (2026-10-13, API 37 Pixel_8 emulator): debug app/test builds and debug lint passed (zero errors, 92 warnings). All four new appearance tests passed, including bounded/transparent imports, source-file deletion, editor recreation, save/reopen/removal, cancellation, five distinct suggestions for sparse logos, contrast, and decoding all four formats. Both focused existing add/edit lifecycle tests passed. Nine of ten notification/widget regressions passed after adapting software captures to Android's hardware bitmaps. The remaining Samsung pixel-purity assertion still fails: the posted QR bitmap readback contains a small number of #010101/#FEFEFE pixels, although decoding and dimensions pass. Direct renderer checks pass; the source of this post/IPC/readback difference is unresolved. Preserve this strict assertion and verify physical Samsung separately. The real Downloads picker was also exercised. Items remain open pending user acceptance.

Source research (2026-10-13): Wikimedia provides free APIs without a key, but retailer-logo coverage has not been verified. Public Action API reads do not require an access token; Wikidata exposes a logo property and Commons provides image URLs and metadata. Respect current access limits and each image's reuse requirements. Logo.dev requires keys, so it does not meet the keyless requirement. Sources: [API authentication](https://www.mediawiki.org/wiki/API:FAQ#Do_I_need_an_access_token?), [logo property](https://www.wikidata.org/wiki/Property:P154), [image metadata](https://www.mediawiki.org/wiki/API:Imageinfo), [Wikimedia access policy](https://www.mediawiki.org/wiki/Wikimedia_APIs/Access_policy), [Logo.dev documentation](https://www.logo.dev/docs/introduction).

## Location evidence

### Card location editor (2026-10-13)

Store locations are edited within Add/Edit card and commit with the card. Location additions, changes and removals remain drafts until Save; cancelling the card discards them. The normal card view lists saved location cards directly, each with a map and an external maps button. The first location requests a current fix when precise location permission is granted; otherwise the editor offers permission, address search and exact coordinates without a default (0, 0) pin. A delayed fix cannot overwrite a manually chosen position.

The radius slider spans 100-500 meters. Numeric entry and saved locations accept 50-1,000 meters; imported radius suggestions are constrained to those limits. Existing stored circles are not migrated automatically.

Verification on the API 37 Pixel_8 emulator: app/test builds and debug lint passed, all 12 PKPass importer unit tests passed, and all six focused location tests passed (the position test was run separately with its emulator-only opt-in). Coverage includes adding a location before saving a new card, draft recreation, card-level discard after removal, custom radii, invalid radii, storage boundaries, and a first location initialized from a synthetic Framework position fix. Physical-device location acquisition and map usability remain pending user review.


- One-shot fixes (`CurrentLocationBackend`) and background monitoring (`GeofenceBackend`) have separate interfaces. Provider code lives in `location/`; `GeofenceCoordinator` handles shared presence and notification decisions.
- Location engine selection: Automatic uses Framework for detected microG or unavailable Google services, otherwise Play services; explicit overrides persist. Google availability is checked directly; an installed Play Store alone does not establish support.
- Monitoring uses 99 store fences plus a movement anchor. Store boundaries are ranked, including large enclosing circles; dense regions shrink according to the nearest unmonitored boundary. Registration updates after meaningful movement, changed store sets/boundaries, or invalid receipts. Unchanged notifications keep their post times.
- JVM scale coverage walks a synthetic line of 1,000 stores spaced 100 m apart in both directions. It checks the exact ordered 99-store selection at 2,000 positions and separately simulates movement-circle replacement with a three-minute delay at 1.5 m/s, checking that every store arrival remains monitored. Selection computes each candidate's geographic distance once before sorting. Both scale tests have a generous ten-second regression timeout; this is not a device benchmark or a guarantee of provider delivery latency. The 99-store provider scenario compiles but still needs an emulator/device rerun; the earlier results below used the 80-store cap.
- The home screen follows arrivals and departures and reconciles saved snapshots. It retains the selected card while it remains nearby; an explicitly opened card stays available independently. Distance labels have been removed. Manual refresh bypasses the app cache; Framework’s current-location API can still return a fix from the past few seconds.
- Movement triggers region recovery with three bounded retries, without a foreground GPS service or periodic polling. Cleanup identities are saved before either provider registers. Obsolete generations/engines are rejected, and Google cleanup IDs are retained if its service becomes unavailable.
- On the API 37 emulator, 40 instrumentation tests verified both engines, real provider ENTER/EXIT delivery, manual recovery, region replacement with a 160-store pool, overlapping stores for one membership, restoration after movement, selection UI, disabling during a refresh, cleanup, home lifecycle, and Framework delivery with Google services and Play Store disabled. These use synthetic location fixes through platform/provider APIs; they do not establish physical-device accuracy, Doze latency, or microG compatibility.
- Verification also passed: 22 local unit tests, debug lint, main/helper release builds, and the optional helper’s delegated lifecycle plus revoked-delegation fallback on the same emulator.
- Fold4 diagnostics recorded accepted Example One ENTER/post at 14:32:19 UTC and EXIT/presence removal at 14:46:25 UTC on 2026-10-05. They do not establish Samsung visibility. Cancellation now targets saved card identities even when Android’s active list omits them; physical Samsung verification remains required.
- Exercise region replacement with 198 additional saved stores on both emulator engines, respecting the 99-store budget and removing an overlapping membership only after its last matching exit. Physical walking coverage remains pending.

## CI configuration

- GitHub Actions was configured for debug/release builds and debug lint of both apps, JVM tests, and focused API 36 instrumentation (storage, PKPass lifecycle, notification settings). Java is pinned in `mise.toml`; Gradle remains pinned by the wrapper. `mikepenz/action-junit-report@v6` publishes JUnit annotations directly without uploading report artifacts; fork and Dependabot PRs use annotation-only reporting.

## Implementation references

- Integration contract and routing: `app/src/main/java/dev/finnes/portemone/notifications/integrations/NotificationBackends.kt`.
- Built-in Samsung protocol: `SamsungNowBar.kt` in that same directory. It does not require another installed app.
- Generic companion client: `PixelNotificationCompat.kt` in that directory; separately installed host: `notification-companion/`.
- Lifecycle regression: `PixelCompatibilityDeviceTest`, opt-in with instrumentation argument `pixelCompatibilityTest` (`lifecycle`, `blocked`, `main-blocked`, `fallback`, `qr`, `barcode`). It requires an emulator; permission/revocation cases need the corresponding real Android state set first. QR/barcode preview modes deliberately leave synthetic cards visible.
- Pixel's fallback must remain available if the legacy layout changes. There is no guaranteed support lifetime for its fixed-height behavior.

- [Custom Android notifications](https://developer.android.com/develop/ui/views/notifications/custom-notification)
- [Notification delegation](https://developer.android.com/reference/android/app/NotificationManager#notifyAsPackage(java.lang.String,java.lang.String,int,android.app.Notification))
- [Geofencing](https://developer.android.com/develop/sensors-and-location/location/geofencing)
- [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/)

## Location validation

Run `scripts/test-location-engines.sh emulator-SERIAL` against an isolated Google APIs / Play Store emulator (API 31+). Enable device Location and Google Location Accuracy first. The command builds the app and test APK, runs unit tests and lint, grants test runtime permissions, and executes the provider/home suites. It rejects physical device serials. Movement fixtures wait for registration replacement before another synthetic trip, and home fixtures establish their own saved snapshots. Provider tests replace mock locations and one test temporarily disables Google packages; fixtures restore their local settings and remove test registrations afterward. Android 37 rate-limits bursts of duplicate notification cancellation; the manual-recovery test waits for a quiet interval before its deliberate dismissal. Results are saved in `app/build/reports/location-engines/instrumentation.txt`; the script fails on JUnit failures even when adb exits successfully.

The application reads card/store definitions from its existing local repository. There is no server synchronization service to run alongside location acquisition.

- [Framework proximity alerts and one-shot location](https://developer.android.com/reference/android/location/LocationManager)
- [AOSP proximity monitoring implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/location/geofence/GeofenceManager.java)
- [microG declared components used for detection](https://github.com/microg/GmsCore/blob/master/play-services-core/src/main/AndroidManifest.xml)

## Notification integration validation

Run `scripts/test-notification-integrations.sh emulator-SERIAL` on an isolated Android 13+ emulator. It builds and installs the main app, legacy helper, and both test APKs, runs unit tests and lint, and checks each instrumentation result. It exercises the helper’s actual setup buttons, recreation, channel preservation, delegation and removal; main/helper permission blocks; simultaneous blocked-and-revoked state; revoked/disabled-helper fallback; multiple cards after a grouping delay; notification actions; and QR/CODE_128 decoding at the displayed pixel size. The Samsung protocol/layout regressions run through `CardPrototypeTest`.

Reports live in `app/build/reports/notification-integrations/`. The runner temporarily changes notification permissions and helper availability, restores both permissions, removes synthetic cards, and restores the original helper connection on completion or failure. Use a dedicated test emulator. Samsung remains built into the main APK; no second Samsung package is introduced. The Pixel helper retains target API 30 and has no location access or service.

Verified on the API 37 emulator: 21 behavior cases (four helper setup/delegation tests, seven permission/routing modes, ten rendering/lifecycle tests), plus fixture administration checks. Main/helper debug lint and release builds passed; the main app’s 22 local unit tests and all 40 location regressions passed after the integration changes. The runner rejects skipped tests as well as failures.

Emulator checks validate notification ownership, actions, fallback and RemoteViews output. They do not prove Samsung’s OEM fullscreen placement, physical Pixel lock-screen presentation, swipe animations or scanner compatibility. Continue physical-device verification separately.

Unified notification settings validation: debug build, main/helper lint, and 26 local unit tests passed. All three new settings instrumentation cases passed on the existing API 36 emulator, with saved appearance preferences restored afterward. Builds used one worker and a 1280 MiB heap; no additional emulator was started.

## Card lifecycle validation

Run `scripts/test-card-lifecycle.sh emulator-SERIAL` on an isolated emulator. It builds the app and instrumentation APK, runs JVM tests and lint, and checks migration, persistent lifecycle changes, duplicate-source identity, transactional package replacement, stale edits, the ten-location limit, Android Open/Share resolution, unsigned import override, review recreation, cancel-without-save, and pixel-size PDF417/Aztec decoding. Then run the existing notification integration runner to cover standard, Samsung protocol and optional helper behavior. Physical Samsung verification remains separate.

PKPass input is bounded (8 MiB compressed, 16 MiB expanded, 128 entries, 256 KiB JSON/localizations, 2 MiB per other entry, and four million pixels per selected artwork). Duplicate paths and path traversal are rejected without extraction. Display previews are bounded; the original package retains full-resolution assets independently of source URI permissions. SQLite stores large original packages in small BLOB rows to avoid Android CursorWindow limits. The database remains covered by encrypted Android backup; provider grants, device presence and geofence receipts are not backed up. Original files can contain issuer authentication data, but import never contacts an issuer or registers the device.

- [Apple Wallet pass schema](https://developer.apple.com/documentation/walletpasses/pass)
- [Apple pass package/signature creation](https://developer.apple.com/documentation/walletpasses/building-a-pass)
- [Apple PKI certificates](https://www.apple.com/certificateauthority/)
- [Apple root SHA-256 fingerprints](https://support.apple.com/105116)

### Appearance editor refinement (2026-10-13)

The live preview leads directly into a compact Background section. Five fixed circular swatches show selection with a checkmark; the adjacent pencil control opens the custom picker, which retains hex entry. Logo replacement and removal share a Logo menu, also reachable by tapping the preview logo. Redundant preview/color captions and the standalone remove control are removed. Keyboard focus is cleared before opening the custom picker.

Debug app/test builds passed. Focused instrumentation passed for logo import, replacement cancellation, removal, recreation and save, and for custom color validation/save in the imported-card lifecycle. Manual API 37 emulator verification covered the real Downloads picker, preview-logo menu activation, and visual inspection of the compact layout. Physical-device review remains pending.

### Saved locations on the card screen (2026-10-13)

The normal, unlocked card screen always includes saved locations below the card, without a Details toggle. Each location card shows its store name, saved address when available, an embedded map of its saved circle, and an Open in maps button. The address and button send a standard geo intent for the saved coordinates without restricting the destination to a particular maps app. Up to ten cards stack vertically as separate lazy-list items so off-screen maps can be disposed. The editor retains its map and area controls. Search-result addresses are now stored separately; moving a pin or changing coordinates clears the address rather than retaining a stale one. Database version 5 adds a nullable address column and preserves existing store coordinates and radii.

Verification: debug app/test builds and nine scoped emulator tests passed, including location-list visibility and geo-intent dispatch, address persistence through reopening and draft recreation, clearing a stale address after moving a pin, upgrades from database versions 3 and 4, card/scanner navigation, imported-card editing, and map-editor rendering/camera stability after lock/unlock. The normal card screen was visually inspected on the API 37 emulator. Intent dispatch was intercepted in tests; destination-app behavior still needs a phone check.

Per-location map cards follow-up: debug app/test builds and four scoped emulator tests passed. The list test scrolls through all ten individual map previews and action buttons, verifies maps intent destinations, and reopens after activity recreation. Card/scanner navigation and imported-card editing also passed. Visual inspection confirmed the saved pin and radius circle render inside a location card with its own Open in maps button.

### EAN-13 (2026-10-13)

EAN-13 is selectable in Add/Edit card and persists by format name. Complete 13-digit ASCII values are required, including the check digit; ZXing validates the checksum. Leading zeroes are retained, and incomplete values are not silently completed. Preview and bitmap rendering enforce the same validation. EAN-13 rendering reserves eleven modules of white space on each side. Existing card formats are not inferred or changed automatically.

Debug app/test builds, two focused validation/storage unit tests, and four emulator instrumentation tests passed. Coverage includes checksum and digit rejection, switching an existing CODE 128 card to EAN-13 and reopening it, leading-zero values, decoded bitmap output at three widths, pure black/white pixels, quiet zones, and shared card-face rendering across all supported formats. Physical scanner acceptance remains to be checked with an EAN-13 card.

### Additional barcode formats (2026-10-13)

Add/Edit card now offers every format writable by ZXing 3.5.3: Code 128, Code 39, Code 93, EAN-13, EAN-8, UPC-A, UPC-E, ITF, Codabar, QR, PDF417, Aztec and Data Matrix. ITF-14 is an additional constrained ITF option matching the 14-digit format in [Google Wallet's barcode type list](https://developers.google.com/wallet/reference/rest/v1/BarcodeType). The list's text-only and unspecified entries are not barcode symbologies. Choices wrap across rows instead of extending a horizontal strip.

EAN/UPC and ITF-14 require complete values with valid check digits. ITF requires an even number of digits; Codabar requires explicit A-D start/end letters. Values remain strings and storage retains the selected format. Data Matrix uses square symbols, integer pixel scaling, the requested character encoding and a white quiet zone. No new dependency is required.

Debug app/test builds, five scoped unit tests and five API 37 emulator instrumentation tests passed. Tests cover format persistence, invalid lengths/check digits/characters, leading zeroes, EAN-13 editing and reopening, decoding every format at 320- and 960-pixel widths, pure black/white output, and shared flat/shaded card faces. Codabar decoding retains its guard letters and UPC/EAN decoding is constrained to the selected format to avoid normalization. Physical scanner acceptance and phone layout review remain pending.

### Release privacy and regression discovery (2026-10-13)

Removed the production membership catalogue, retailer names, logos, retailer-specific rendering branches and a private reference screenshot. Test memberships now use synthetic values and generic brands in test source sets only. Shipped demos use the generic card icon. Legacy location-only databases recover plain cards from stored card identifiers and preserve circles without inserting membership values; current card databases retain their user data and fall back to the generic icon for retired built-in artwork.

The card screen observes screen-off, user-present and activity resume events. Relocking hides store maps, private fields and editing controls even when the show-when-locked activity survives; the scanning surface remains available as designed. A real emulator lock/unlock test verifies hiding and the app's unlock action.

Instrumentation CI now discovers tests by default and excludes only explicit RequiresDeviceSetup annotations. A discovery-only run found 74 ordinary tests across 21 classes, including barcode, appearance, location editing and lock privacy. Device/provider/companion scenarios retain their dedicated setup paths. CI fails on skipped, failed, empty or missing ordinary test results; the report checker was exercised against each outcome.

Verification: ten scoped JVM tests and 42 distinct scoped emulator tests passed after fixing stale synthetic-fixture expectations and exercising the real unlock action. Coverage includes legacy migration, persistence, card navigation, appearance, barcode decoding, carousel selection, diagnostics and shared Samsung notification payload/rendering. Notification readback checks allow a one-level black/white endpoint shift observed on the emulator; direct renderer tests still require exact black/white, posted codes must decode, and intermediate shades are rejected. This does not establish physical Samsung animation or scanner acceptance.

Both release APK builds and release lint passed. Main lint decreased from 93 to 57 warnings with zero errors; companion lint has no issues. Safe preference/theme/resource cleanups preserve the companion SDK, Samsung reflection/payload fields and synchronous geofence commits. Remaining warnings are still reported. Source and release DEX inspection found no original private membership values or test membership catalogue.

### Notification scanner navigation (2026-10-13)

Notification taps open the enlarged, brightened code in a separate task excluded from Recents. The app launcher retains the wallet task. Card details require keyguard dismissal, and relocking replaces the entire details screen with the scanner. Back closes a notification scan without unlocking. Returning through the launcher after Home from either the scanner or its details screen shows the wallet overview.

Debug app and instrumentation builds passed. Five distinct scoped API 37 emulator tests passed across targeted runs: lock/unlock/relock with brightness restoration and system Back, ordinary card navigation, launcher return from notification scanning and details, and notification selection with decoded membership codes. The lock test uses an emulator with a swipe lock; physical Samsung PIN/biometric dismissal and Now Bar tap behavior still need phone verification. Notification rendering, Samsung payload fields and the legacy helper SDK were not changed.

Follow-up navigation review: notification screens explicitly remove their task on Back, including Plain cards and the unlocked details screen. Debug app/test builds and six scoped emulator tests passed across targeted runs. Added coverage verifies Back after Plain-card recreation and canceling an actual PIN unlock prompt: the code remains visible, details remain closed, recreation retains full brightness, and Back exits while the device stays locked. The test restores the emulator's original lock configuration. Successful PIN/biometric entry and Samsung Now Bar behavior still require physical-phone verification.
