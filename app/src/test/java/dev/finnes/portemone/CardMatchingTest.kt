// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.finnes.portemone.location.NearbyStores
import org.junit.Assert.*
import org.junit.Test

class CardMatchingTest {
    @Test
    fun membershipValuesRoundTripThroughQrWithoutNumericConversion() {
        val expected =
            mapOf("fixture-one" to "000000000000000001", "fixture-two" to "000000000000000002")
        for (card in TestMemberships.cards) {
            assertEquals(expected.getValue(card.id), card.rawValue)
            val matrix = MultiFormatWriter().encode(card.rawValue, BarcodeFormat.QR_CODE, 512, 512)
            val pixels =
                IntArray(512 * 512) { if (matrix[it % 512, it / 512]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            val decoded = MultiFormatReader().decode(
                BinaryBitmap(
                    HybridBinarizer(
                        RGBLuminanceSource(
                            512,
                            512,
                            pixels
                        )
                    )
                )
            )
            assertEquals(expected.getValue(card.id), decoded.text)
            assertEquals(BarcodeFormat.QR_CODE, decoded.barcodeFormat)
        }
    }

    @Test
    fun eachStoreSelectsOnlyItsOwnMembership() {
        val firstCard = TestMemberships.cards.first { it.brandName == "Example One" }
        val secondCard = TestMemberships.cards.first { it.brandName == "Example Two" }
        assertEquals(
            listOf(firstCard),
            NearbyStores.matching(TestMemberships.cards, setOf(firstCard.stores.single().id))
        )
        assertEquals(
            listOf(secondCard),
            NearbyStores.matching(TestMemberships.cards, setOf(secondCard.stores.single().id))
        )
        assertTrue(
            NearbyStores.matching(
                TestMemberships.cards,
                setOf("test", "demo-area", "unknown")
            ).isEmpty()
        )
    }

    @Test
    fun leavingOneBrandKeepsTheOtherUntilItsOwnExit() {
        val firstCard = TestMemberships.cards[0]
        val secondCard = TestMemberships.cards[1]
        val firstCardStore = setOf(firstCard.stores.single().id)
        val secondCardStore = setOf(secondCard.stores.single().id)
        var inside = NearbyStores.transition(emptySet(), firstCardStore, true)
        inside = NearbyStores.transition(inside, secondCardStore, true)
        assertEquals(
            setOf(firstCard, secondCard),
            NearbyStores.matching(TestMemberships.cards, inside).toSet()
        )
        inside = NearbyStores.transition(inside, firstCardStore, false)
        assertEquals(listOf(secondCard), NearbyStores.matching(TestMemberships.cards, inside))
        inside = NearbyStores.transition(inside, secondCardStore, false)
        assertTrue(NearbyStores.matching(TestMemberships.cards, inside).isEmpty())
    }

    @Test
    fun multipleStoresForOneMembershipDoNotDuplicateTheCard() {
        val card = TestMemberships.cards.first()
            .let { it.copy(stores = it.stores + it.stores.single().copy(id = "other")) }
        assertEquals(
            listOf(card),
            NearbyStores.matching(listOf(card), card.stores.map { it.id }.toSet())
        )
    }

    @Test
    fun coordinatesAndRadiiArePreservedWhenSelectingNearbyStores() {
        val stores = TestMemberships.cards.flatMap { it.stores }
        val selected = NearbyStores.nearest(stores, 55.000000, 12.000000)
        assertEquals(55.000000, selected[0].latitude, 0.0)
        assertEquals(12.000000, selected[0].longitude, 0.0)
        assertEquals(100f, selected[0].radiusMeters)
        assertEquals(55.050000, selected[1].latitude, 0.0)
        assertEquals(12.050000, selected[1].longitude, 0.0)
        assertEquals(150f, selected[1].radiusMeters)
        assertTrue(TestMemberships.cards.all { it.barcodeType == BarcodeType.QR_CODE })
    }
}
