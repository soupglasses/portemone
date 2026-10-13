// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

/** Synthetic memberships used only by tests; never included in a shipping APK. */
internal object TestMemberships {
    val cards = listOf(
        Card(
            "fixture-one", "Example One", "Card", R.drawable.ic_card,
            0xFF146C64.toInt(), -1, BarcodeType.QR_CODE, "000000000000000001",
            stores = listOf(StoreLocation("fixture-one-store", "Example One", 55.0, 12.0, 100f))
        ),
        Card(
            "fixture-two", "Example Two", "Card", R.drawable.ic_card,
            0xFF625B71.toInt(), -1, BarcodeType.QR_CODE, "000000000000000002",
            stores = listOf(StoreLocation("fixture-two-store", "Example Two", 55.05, 12.05, 150f))
        )
    )
}
