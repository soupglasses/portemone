<!--
SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
SPDX-License-Identifier: EUPL-1.2
-->

# Device validation

Check that nearby cards are ready to scan, retain their saved store radii, and
vanish after leaving. Opening the app and refreshing should recover promptly;
background geofence delivery can take minutes.

Use [Development](../DEVELOPMENT.md#ci-and-testing) for setup and test commands,
and [mise.toml](../mise.toml) for the configured tasks. Run tests scoped to the
changed behavior during development. The runners below cover broader provider
and integration scenarios when those boundaries change; they also build APKs,
run JVM tests and lint. Use an isolated emulator because they change permissions,
mock locations and package availability.

Historical run counts and development reports are in the
[October 2026 archive](history/validation-2026-10.md). They are not a current
full-suite result. Track production work and unresolved limitations in
[GitHub issues](https://github.com/soupglasses/portemone/issues), and distinguish
emulator evidence from physical-device results when reporting verification.

## Location monitoring

Monitoring selects up to 99 store fences plus one movement circle around the last
selection position. It ranks store boundaries, accounting for saved radii and
large enclosing circles. The movement radius is half the distance to the nearest
unmonitored boundary, clamped to 100 m–20 km; with no unmonitored stores it is
20 km. Leaving that circle requests a fresh selection. This provides an early
refresh margin, not a guarantee of timely Android delivery.

Automatic engine selection uses Framework for detected microG or unavailable
Google services, otherwise Play services. One-shot foreground fixes and background
monitoring are separate. Manual refresh requests a fresh fix; Framework may still
return a fix from the past few seconds. No server synchronization service is
needed. See [card saves and reconciliation](../DEVELOPMENT.md#saving-cards-and-updating-nearby-notifications)
for how edits reach monitoring without waiting for GPS.

Run `mise run test:location -- emulator-SERIAL` on an isolated Google APIs / Play
Store emulator, API 31+. Enable device Location and Google Location Accuracy.
The [runner](../scripts/test-location-engines.sh) rejects physical devices and
checks instrumentation output, not just adb's exit code. Results are written to
`app/build/reports/location-engines/instrumentation.txt`.

Verify both engines through actual provider ENTER/EXIT events, foreground recovery,
changed radii, engine changes and cleanup after disabling monitoring. Exercise
movement replacement with 198 additional saved stores, the 99-store budget, and
overlapping circles for one card: leaving one circle must not remove the card
until its last matching exit. Confirm browsing keeps the selected card while it
remains nearby. Provider fixtures restore local settings and registrations;
one scenario temporarily disables Google packages.

JVM scale coverage walks a synthetic line of 1,000 stores spaced 100 m apart in both
directions. It checks the exact ordered 99-store selection at 2,000 positions and
separately simulates movement-circle replacement with a three-minute delay at 1.5
m/s, checking that every store arrival remains monitored. Selection computes each
candidate's geographic distance once before sorting. Both scale tests have a generous
ten-second regression timeout; this is not a device benchmark or a guarantee of
provider delivery latency. Both Play services and Framework passed the 99-store
provider scenario on an isolated API 36 emulator; the older archived provider
results used the 80-store cap.

Physical walking, driving, very dense overlapping stores, Doze delivery latency
and microG compatibility still need device evidence. Synthetic provider fixes
and JVM simulations cannot establish these.

## Notification integrations

Run `mise run test:helper -- emulator-SERIAL` on an isolated Android 13+ emulator.
The [runner](../scripts/test-notification-integrations.sh) installs both apps and
test APKs, exercises actual helper setup and delegation, and rejects failed or
skipped cases. Reports are in `app/build/reports/notification-integrations/`.
It restores notification permissions and the original helper connection and
removes synthetic cards on completion or failure.

Verify Automatic/Standard settings and persistence, helper opt-in/out, main and
helper permission blocks, revoked delegation, disabled-helper fallback, and the
combined blocked-and-revoked state. A block must remain a block across hosts.
Check multiple cards after delayed grouping, lock/unlock, selection actions,
updates and removal on EXIT. Cancellation must reach the owning notification
package even if Android omits it from the active notification list.

Decode displayed QR and CODE_128 values at their final pixel size. Direct renderer
output must be exact black on white with intact quiet zones and integer scaling.
Emulator notification readback permits the observed one-level endpoint shift,
but must reject intermediate shades and still decode. Inspect swipe/open
animations as well as still images.

Samsung support is built into the main app; the optional generic companion is a
separate legacy-layout host without location access. Keep its older target SDK
isolated. Emulator protocol/layout checks do not prove physical Samsung Now Bar
placement, Pixel lock-screen presentation or scanner acceptance. Preserve the
working Samsung integration and verify changes on a phone separately. The
companion layout's continued support across OS releases is not guaranteed.

API 37.2 preview verification found a companion rendering regression: the posted
compact barcode was reduced from 522 to 126 pixels (48 dp), while its view retained
the original size. The strict posted-pixel test fails. The runtime now reduces bitmap
and encoded-data Icons; changing transport alone does not repair it. Delegation,
permission blocks, fallback and Samsung payload tests passed on that emulator.
Treat full-card companion rendering on this preview as unverified until corrected;
do not infer support from a successful post.

## Cards, editing and privacy

Run `mise run test:cards -- emulator-SERIAL` for storage and PKPass lifecycle
changes. The [runner](../scripts/test-card-lifecycle.sh) covers migration,
persistence, transactional package replacement, source identity, stale edits,
the ten-location limit, Android Open/Share, explicit unsigned-import override,
review recreation, cancellation and barcode decoding. Use focused UI tests for
editor, navigation and presentation changes, and inspect those flows on an
emulator or device. Changes affecting rendered notifications also need the
relevant integration checks above.

Fresh wallets start empty. Legacy location-only databases recover plain cards
and saved circles without bundled membership values. Verify string barcode
values, leading zeroes, format validation, pixel-size decoding and quiet zones.
For logo/color edits, check file-picker cancellation, recreation, save/reopen,
removal and legibility. Location changes remain drafts until the card is saved;
cancellation must discard them, and a delayed GPS fix must not overwrite a
manually chosen position. Check radius editing, saved map circles and external
maps intents.

Exercise notification scans through lock, unlock, relock and Back, including a
cancelled unlock prompt. Codes remain available as designed, while maps, private
fields and edit controls require unlocking. Launcher return should show the
wallet; notification scanning uses a separate task excluded from Recents.
Physical Samsung PIN/biometric dismissal and Now Bar taps need phone testing.

PKPass verification is offline and invalid/untrusted packages require explicit
acceptance; structural, size and encoding failures cannot be overridden. Original
packages may contain issuer authentication data and remain in local storage for
export. Import does not contact issuers or register devices. Check diagnostics
and shared reports for membership values and precise coordinates.

## References

- [Tested devices](../README.md#tested-devices)
- [Custom notifications](https://developer.android.com/develop/ui/views/notifications/custom-notification)
- [Notification delegation](https://developer.android.com/reference/android/app/NotificationManager#notifyAsPackage(java.lang.String,java.lang.String,int,android.app.Notification))
- [Geofencing](https://developer.android.com/develop/sensors-and-location/location/geofencing)
- [Framework location APIs](https://developer.android.com/reference/android/location/LocationManager)
- [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
- [Apple Wallet pass schema](https://developer.apple.com/documentation/walletpasses/pass)
- [Apple pass signatures](https://developer.apple.com/documentation/walletpasses/building-a-pass)
- [Apple PKI certificates](https://www.apple.com/certificateauthority/)
