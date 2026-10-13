<!--
SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
SPDX-License-Identifier: EUPL-1.2
-->

# Portemoné

## Product goal

Make the correct loyalty card available to scan when someone reaches a store, ideally directly on the lock screen. Opening the app is a fast recovery path. The experience should feel like presenting a real card, with recognizable branding and a large, dependable code.

## Experience to preserve

- Nearby cards appear quietly and disappear after leaving their saved store circles. Background geofence delays of a few minutes are acceptable; foreground recovery should be prompt.
- Manual refresh requests a fresh location. Loading and completion feel intentional, while browsing stays stable without surprise reordering.
- Compact cards are easy to distinguish. Nearby cards sort by distance, and the app presents one scannable card at a time with clear navigation between alternatives.
- QR and CODE_128 values remain strings and render locally at their final pixel size: black on white, intact quiet zones, no image rescaling or smoothing.
- Location matching stays on-device, respects edited radii, and uses a bounded nearby geofence set. Diagnostics explain failures without exposing membership values or precise coordinates.
- Notification permissions and privacy choices remain meaningful. Health checks report measured state and clearly identify what cannot be verified.

## Engineering direction

Prefer simple, concrete code. Add abstractions when they remove current duplication or isolate a platform dependency. Keep UI state, persistence and system side effects separate without adding layers that only forward calls.

Keep documentation focused on setup, workflows and non-obvious constraints. Link to authoritative configuration instead of maintaining file inventories or duplicating settings.

Maintain SPDX attribution when adding files. Preserve upstream licenses and provenance for imported code, assets and skills.

Follow the [commit format and signing requirements](DEVELOPMENT.md#commits-and-tags).

The main app uses Kotlin and Compose; notification layouts use RemoteViews. Keep device-specific behavior behind the shared posting, fallback, and cancellation boundary in `app/src/main/java/dev/finnes/portemone/notifications/integrations/`.

Preserve the working Samsung full-card/Now Bar integration. The optional generic legacy helper, tested on Pixel and Xiaomi, lives in `notification-companion/`; its older target stays isolated from the modern main app. New integrations should be independently replaceable, with standard notifications available when an integration is unavailable. A notification block should remain a block across hosts.

Keep experiments outside normal product flows. Archived research lives in `research/pixel-notifications/`; remaining production work and known limitations belong in [GitHub issues](https://github.com/soupglasses/portemone/issues).

## Evidence of progress

Evaluate actual emulator/device behavior, including lock/unlock, delayed grouping, multiple cards, permission changes, and ENTER/EXIT removal. Decode rendered codes and inspect animations; a successful notification post or first screenshot alone does not establish support. Distinguish emulator results from physical Samsung/Pixel verification.

Build and test the affected modules with Gradle. Use the opt-in helper instrumentation tests for delegation and fallback changes. Keep the relevant GitHub issues current with verified improvements and unresolved behavior, especially anything that could regress Samsung support.
