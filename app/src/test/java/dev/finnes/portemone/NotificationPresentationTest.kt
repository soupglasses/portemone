// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.notifications.integrations.NotificationHost
import dev.finnes.portemone.notifications.integrations.NotificationPresentation
import dev.finnes.portemone.notifications.integrations.notificationHost
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPresentationTest {
    @Test fun automaticUsesBuiltInFullCardsOnSupportedSamsungPhones() {
        assertEquals(NotificationHost.SAMSUNG, notificationHost(NotificationPresentation.AUTOMATIC, true, true))
    }
    @Test fun automaticUsesTheOptionalHelperOnOtherPhones() {
        assertEquals(NotificationHost.HELPER, notificationHost(NotificationPresentation.AUTOMATIC, false, false))
    }
    @Test fun olderSamsungPhonesKeepStandardNotifications() {
        assertEquals(NotificationHost.STANDARD, notificationHost(NotificationPresentation.AUTOMATIC, false, true))
    }
    @Test fun standardChoiceOverridesEveryDeviceDefault() {
        for (samsung in listOf(false, true)) assertEquals(NotificationHost.STANDARD,
            notificationHost(NotificationPresentation.STANDARD, samsung, samsung))
    }
}
