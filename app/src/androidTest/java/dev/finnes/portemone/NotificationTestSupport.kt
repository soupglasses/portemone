// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertNotNull

/** Wait for the observable destination, including launches from delegated PendingIntents. */
internal fun awaitOpenedCard(cardId: String): CardActivity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    var opened: CardActivity? = null
    val deadline = SystemClock.elapsedRealtime() + 5_000
    while (opened == null && SystemClock.elapsedRealtime() < deadline) {
        instrumentation.runOnMainSync {
            opened = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<CardActivity>().firstOrNull { it.intent.getStringExtra(CardActivity.CARD_ID) == cardId }
        }
        if (opened == null) Thread.sleep(50)
    }
    assertNotNull("The notification must open its own card", opened)
    return requireNotNull(opened)
}
