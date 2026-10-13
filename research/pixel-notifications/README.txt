# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

Archived Pixel notification experiments

These experiments are excluded from app builds. Production code lives in
app/.../notifications/integrations/ and the
:notification-companion module. Use PixelCompatibilityDeviceTest for its
opt-in emulator lifecycle checks.

These older experiments compare native templates, promoted notifications,
custom layouts, translation/clipping, a bounded foreground-service color test,
and legacy notification delegation. Demonstration barcode data is synthetic.
The public-legacy-helper and public-legacy-isolated variants were prepared but
not run.

The target-sdk-control project builds identical notification layouts targeting
30 and 36. Its source-only project can be built with the root Gradle wrapper's
-p option. It is not part of the production Gradle settings.

To repeat the older app-side matrix, temporarily restore the three archived
files to their original androidTest/debug source sets and debug manifest. Run
only on an emulator using the pixelNotificationVariant instrumentation argument;
the test checks for sdk_gphone. FGS trials require the archived debug service.
Delegated modes require the target-sdk-control legacy APK and its delegation
setup. Remove these temporary debug components after research.

Verified findings on the tested API 37 Pixel emulator:
- Modern custom/BigPicture layouts retain the system icon gutter.
- Legacy target 30 provides an undecorated full-width surface.
- A fixed-height outer RemoteViews root can display the complete card on the
  lock screen immediately; wrap_content clips it to the small-view budget.
- Separate compact/expanded instances avoid shared bitmap downscaling.
- Bitmap-backed Icon actions preserve compact code resolution.
- Two cards can later be automatically grouped into a collapsed summary.

These behaviors depend on Android implementation details and may change with
updates or differ on other phones.
