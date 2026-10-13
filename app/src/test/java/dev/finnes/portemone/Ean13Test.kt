// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import org.junit.Assert.*
import org.junit.Test

class Ean13Test {
    @Test
    fun acceptsCompleteValuesIncludingLeadingZeroes() {
        for (value in listOf("5901234123457", "0123456789012")) {
            val card = TestCard.card.copy(
                id = "ean-fixture",
                barcodeType = BarcodeType.EAN_13,
                rawValue = value
            )
            validateCard(card)
            assertEquals(
                card,
                CardStorageCodec.decode(card.id, CardStorageCodec.encode(card), card.stores)
            )
        }
    }

    @Test
    fun rejectsIncompleteNonNumericAndIncorrectCheckDigitsWithoutChangingThem() {
        for (value in listOf(
            "590123412345",
            "59012341234567",
            "5901234123458",
            "590123 123457",
            "５９０１２３４１２３４５７",
            "590123412345x"
        )) {
            val card = TestCard.card.copy(
                id = "ean-fixture",
                barcodeType = BarcodeType.EAN_13,
                rawValue = value
            )
            assertThrows(IllegalArgumentException::class.java) { validateCard(card) }
            assertEquals(value, card.rawValue)
        }
    }
}
