// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.cards.CardStorageCodec
import org.junit.Assert.*
import org.junit.Test

class BarcodeFormatsTest {
    private val numeric = mapOf(
        BarcodeType.EAN_8 to "96385074",
        BarcodeType.EAN_13 to "5901234123457",
        BarcodeType.UPC_A to "012345678905",
        BarcodeType.UPC_E to "01234565",
        BarcodeType.ITF_14 to "10012345000017"
    )

    @Test
    fun everyFormatValidatesAndSurvivesStorage() {
        for (type in BarcodeType.entries) {
            val value =
                numeric[type] ?: if (type == BarcodeType.CODABAR) "A0012345B" else "0000012345"
            val card =
                TestCard.card.copy(id = "format-fixture", barcodeType = type, rawValue = value)
            validateCard(card)
            assertEquals(
                card,
                CardStorageCodec.decode(card.id, CardStorageCodec.encode(card), card.stores)
            )
        }
    }

    @Test
    fun numericFormatsRejectMissingOrInvalidCheckDigits() {
        for ((type, value) in numeric) {
            for (invalid in listOf(
                value.dropLast(1),
                value + "0",
                value.dropLast(1) + ((value.last().digitToInt() + 1) % 10),
                "x" + value.drop(1)
            )) {
                assertThrows("$type must reject $invalid", IllegalArgumentException::class.java) {
                    validateCard(
                        TestCard.card.copy(
                            id = "invalid-fixture",
                            barcodeType = type,
                            rawValue = invalid
                        )
                    )
                }
            }
        }
    }

    @Test
    fun itfAndCodabarRejectValuesThatWouldNeedChanging() {
        for ((type, value) in listOf(
            BarcodeType.ITF to "123",
            BarcodeType.ITF to "12ab",
            BarcodeType.CODABAR to "123456",
            BarcodeType.CODABAR to "A123E",
            BarcodeType.CODABAR to "A12xB"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                validateCard(
                    TestCard.card.copy(
                        id = "invalid-fixture",
                        barcodeType = type,
                        rawValue = value
                    )
                )
            }
        }
    }
}
