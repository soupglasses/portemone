// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.finnes.portemone.cards.LocalCardRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Resolve the actual launcher Activity theme, including Android's night/version qualifiers. */
@RunWith(AndroidJUnit4::class)
class StartupThemeTest {
    @org.junit.Before
    fun establishMembershipFixtures() {
        val repository =
            LocalCardRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val ids = repository.cards().map { it.id }.toSet()
        TestMemberships.cards.filter { it.id !in ids }.forEach { repository.saveCard(it) }
    }

    @Test
    fun darkNativeLaunchWindowAndSystemBarsStayDarkBeforeComposeStarts() =
        checkNativeTheme(night = true)

    @Test
    fun lightNativeLaunchWindowMatchesTheLightApplicationSurface() = checkNativeTheme(night = false)

    @Test
    fun nativeSplashKeepsTheMidnightAdaptiveBrandIconInBothThemeModes() {
        listOf(false, true).forEach { night ->
            val context = launcherTheme(night)
            val attributes = context.obtainStyledAttributes(
                intArrayOf(
                    android.R.attr.windowSplashScreenAnimatedIcon,
                    android.R.attr.windowSplashScreenIconBackgroundColor,
                )
            )
            try {
                assertEquals(
                    "The platform must not add a second light badge around the branded icon",
                    Color.TRANSPARENT,
                    attributes.getColor(1, Color.MAGENTA)
                )
                val icon =
                    checkNotNull(attributes.getDrawable(0)) { "A native splash icon is required" }
                assertTrue(
                    "Launch must preserve the deliberate adaptive brand background",
                    icon is AdaptiveIconDrawable
                )
                val bitmap = Bitmap.createBitmap(288, 288, Bitmap.Config.ARGB_8888)
                try {
                    icon.setBounds(0, 0, bitmap.width, bitmap.height)
                    icon.draw(Canvas(bitmap))
                    assertEquals(
                        "The brand background stays midnight in both theme modes",
                        Color.rgb(33, 29, 71),
                        bitmap.getPixel(24, 144)
                    )
                    assertEquals(
                        "The adaptive icon mask must keep its outer corner transparent",
                        Color.TRANSPARENT,
                        bitmap.getPixel(0, 0)
                    )
                } finally {
                    bitmap.recycle()
                }
            } finally {
                attributes.recycle()
            }
        }
    }

    private fun checkNativeTheme(night: Boolean) {
        val context = launcherTheme(night)
        val attributes = context.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.windowBackground,
                android.R.attr.windowSplashScreenBackground,
                android.R.attr.statusBarColor,
                android.R.attr.navigationBarColor,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar,
            )
        )
        try {
            val background = attributes.getColor(0, Color.MAGENTA)
            val luminance = ColorUtils.calculateLuminance(background)
            assertTrue(
                "Night startup must never fall back to a light window",
                !night || luminance < .05
            )
            assertTrue("Light startup must retain its light surface", night || luminance > .8)
            assertEquals(
                "Native splash and first Activity window must share one surface",
                background,
                attributes.getColor(1, Color.MAGENTA)
            )
            assertEquals(background, attributes.getColor(2, Color.MAGENTA))
            assertEquals(background, attributes.getColor(3, Color.MAGENTA))
            assertEquals(
                "Status-bar icon contrast must follow the native surface",
                !night,
                attributes.getBoolean(4, night)
            )
            assertEquals(
                "Navigation-bar icon contrast must follow the native surface",
                !night,
                attributes.getBoolean(5, night)
            )
            if (Build.VERSION.SDK_INT >= 34) {
                val compose =
                    if (night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                assertEquals(
                    "Startup must match the dynamic Compose background on modern Android",
                    compose.background.toArgb(),
                    background
                )
            }
        } finally {
            attributes.recycle()
        }
    }

    private fun launcherTheme(night: Boolean): ContextThemeWrapper {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themedConfiguration = context.createConfigurationContext(configuration)
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0
        )
        return ContextThemeWrapper(themedConfiguration, activity.themeResource)
    }
}
