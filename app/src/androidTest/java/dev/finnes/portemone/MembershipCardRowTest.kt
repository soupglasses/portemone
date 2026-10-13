// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.finnes.portemone.ui.theme.WalletTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MembershipCardRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sameBrandMembershipsCanBeRecognizedAndSelectedByNumber() {
        val first = TestMemberships.cards.first()
        val second = first.copy(
            id = "second-membership",
            rawValue = "000000000000000003",
            displayText = null
        )
        var selected: String? = null
        compose.setContent {
            WalletTheme {
                Column(Modifier.width(320.dp)) {
                    listOf(first, second).forEach { card ->
                        MembershipCardRow(card, nearby = false, onClick = { selected = card.id })
                    }
                }
            }
        }
        compose.onAllNodesWithText("Example One Card").assertCountEquals(2)
        compose.onNodeWithText("00 0000 0000 0000 0001").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(first.id, selected) }
        compose.onNodeWithText("00 0000 0000 0000 0003").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(second.id, selected) }
    }
}
