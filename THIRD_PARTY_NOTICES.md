<!--
SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
SPDX-License-Identifier: EUPL-1.2
-->

# Third-party notices

Dependencies and external assets retain their own terms, separate from Portemoné's license.
`./gradlew cyclonedxBom` inventories resolved release dependencies and their declared
licenses. An SBOM does not certify license compatibility or cover all embedded native code.

Bundled notices:

- [Apache 2.0](LICENSES/Apache-2.0.txt), including the Gradle wrapper.
- [Bouncy Castle 1.86](licenses/LICENSE.Bouncy-Castle-1.86.txt).
- [MapLibre Native 13.6.1](licenses/LICENSE.MapLibre-Native-13.6.1.txt).

## Android skills and icons

The skills in `.agents/skills/` were installed through Android CLI from Google's
[Android skills repository](https://github.com/android/skills), under
[Apache-2.0](https://github.com/android/skills/blob/main/LICENSE.txt), not EUPL.
Sources:

- `android-cli`: [`devtools/android-cli`](https://github.com/android/skills/tree/main/devtools/android-cli)
- `adaptive`: [`jetpack-compose/adaptive`](https://github.com/android/skills/tree/main/jetpack-compose/adaptive)
- `appfunctions`: [`device-ai/appfunctions`](https://github.com/android/skills/tree/main/device-ai/appfunctions)
- `edge-to-edge`: [`system/edge-to-edge`](https://github.com/android/skills/tree/main/system/edge-to-edge)
- `testing-setup`: [`testing/testing-setup`](https://github.com/android/skills/tree/main/testing/testing-setup)

[REUSE.toml](REUSE.toml) records their Google attribution and license without
rewriting the imported skill files. The navigation/action icons derived from
[Material Design Icons](https://github.com/google/material-design-icons) retain
Google attribution and Apache-2.0 headers in their XML resources.

Apple root certificates retain Apple attribution in their `.license` sidecar.
`LicenseRef-Apple-PKI` records unresolved redistribution terms; it does not grant
permission to redistribute them or apply EUPL to them.

## Google Play Services distribution

The intended Google-enabled release channel is signed APKs on GitHub Releases.
In the [CMA's 2022 report, Appendix E, paragraph 47](https://assets.publishing.service.gov.uk/media/62a0d5cbe90e07039ba54eee/Appendix_E_-_Google_agreements_with_device_manufacturers_and_app_developers.pdf#page=17),
Google stated that Play Services API availability does not depend on how an app is
installed. Google's [setup guide](https://developers.google.com/android/guides/setup)
also instructs developers to embed `play-services-location:21.4.0` in their apps.

These sources support off-Play distribution; they are not a separate redistribution
license grant. The client library retains its [SDK terms](https://developer.android.com/studio/terms)
and [notice obligations](https://developers.google.com/android/guides/opensource),
separate from Portemoné's license. Sources reviewed on 2026-10-13.

Before distribution, review Google Play Services terms, retailer artwork permissions,
Apple certificate redistribution and complete APK license notices.
Tracked in [#19](https://github.com/soupglasses/portemone/issues/19).
