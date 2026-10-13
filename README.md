<!--
SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
SPDX-License-Identifier: EUPL-1.2
-->

<p align="center">
  <img src="docs/assets/portemone.svg" width="160" height="160" alt="Portemoné logo">
</p>

<h1 align="center">Portemoné</h1>

<p align="center">Your loyalty card, ready when you reach the store.</p>

An Android wallet for loyalty cards, with PKPass import, scannable codes,
home-screen widgets and nearby-card notifications. Requires Android 12+.

## Tested devices

Full-card notifications show the card’s branding and scannable code. The optional `notification-companion` supports full-card
notifications across phone brands; tested devices and limitations are listed below.

| Device / system | Card appearance | Status |
| --- | --- | --- |
| Samsung Galaxy Z Fold4 / One UI 8.0 | Automatic (built-in) | Working |
| Google Pixel 8 (emulator) | Automatic (companion) | Working in emulator |
| Xiaomi / HyperOS 2 | Automatic (companion) | Visible border around the card |

Choose **Automatic** under **Settings → Notifications → Card appearance**.
Set up the companion there when needed; standard notifications provide a fallback.

## Card formats

Create cards with any of these barcode formats:

- Linear: Code 128, Code 39, Code 93, EAN-13, EAN-8, UPC-A, UPC-E,
  ITF, ITF-14 and Codabar.
- 2D: QR, PDF417, Aztec and Data Matrix.
- Plain: cards without a barcode.

Codes render locally. Membership values retain leading zeroes; EAN/UPC and
ITF-14 require complete values with valid check digits. Logo images can be
imported from files; camera and image-based barcode capture are not yet supported.

## Install

[Build from source](DEVELOPMENT.md#build).

<details>
<summary>Obtainium setup</summary>

[Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522dev.finnes.portemone%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252Fsoupglasses%252Fportemone%2522%252C%2522author%2522%253A%2522soupglasses%2522%252C%2522name%2522%253A%2522Portemon%25C3%25A9%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255Eportemone%255C%255C%255C%255C.apk%2524%255C%2522%257D%2522%257D) · [GitHub Releases](https://github.com/soupglasses/portemone/releases)

Open the add link on your Android device. It selects the main app automatically;
the notification companion is installed separately.

</details>

<!-- Publish the signed main APK as portemone.apk; the Obtainium link filters by that name. -->

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for building, testing, signing and releases.

## Privacy and license

Cards and store matching stay on-device. Maps and address search use external
services. Android backup may include wallet data; notifications may show card codes
on the lock screen. Tapping a card notification opens its enlarged code at full
brightness. Card details, including saved locations, require unlocking. Back closes
the notification scanner; launching Portemoné from its app icon opens the wallet.

Copyright © 2026 Sofie Finnes. Original code: [EUPL 1.2](LICENSE).
Project logo and icon artwork: [all rights reserved](LICENSES/LicenseRef-All-Rights-Reserved.txt),
as identified by their SPDX headers or `.license` sidecars.
[Third-party terms](THIRD_PARTY_NOTICES.md) apply separately.
