// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.location.LocationEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationEngineSelectionTest {
    @Test fun automaticUsesFrameworkForMicroGEvenWhenGoogleApisAppearAvailable() {
        assertEquals(LocationEngine.FRAMEWORK, LocationEngine.select(LocationEngine.AUTOMATIC, true, true))
        assertEquals(LocationEngine.FRAMEWORK, LocationEngine.select(LocationEngine.AUTOMATIC, true, false))
    }
    @Test fun automaticRequiresWorkingGoogleServicesRatherThanJustAStorePackage() {
        assertEquals(LocationEngine.PLAY_SERVICES, LocationEngine.select(LocationEngine.AUTOMATIC, false, true))
        assertEquals(LocationEngine.FRAMEWORK, LocationEngine.select(LocationEngine.AUTOMATIC, false, false))
    }
    @Test fun explicitPreferenceWinsInEveryEnvironment() {
        for (engine in listOf(LocationEngine.PLAY_SERVICES, LocationEngine.FRAMEWORK)) {
            for (microG in listOf(false, true)) for (google in listOf(false, true)) {
                assertEquals(engine, LocationEngine.select(engine, microG, google))
            }
        }
    }
}
