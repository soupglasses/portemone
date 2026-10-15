// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.graphics.Bitmap
import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.finnes.portemone.cards.LocalCardRepository
import dev.finnes.portemone.location.LocationSettings
import dev.finnes.portemone.location.LocationEngine
import dev.finnes.portemone.notifications.CardNotificationManager
import dev.finnes.portemone.notifications.integrations.CompanionNotificationClient
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Captured only by scripts/capture-readme-screenshots.sh on a disposable AVD. */
@RunWith(AndroidJUnit4::class)
@RequiresDeviceSetup("scripts/capture-readme-screenshots.sh resets a dedicated emulator and exports screenshots")
class ReadmeScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun captureWalletNearbyCodeFormatsAndLockScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val theme = InstrumentationRegistry.getArguments().getString("screenshotTheme", "light")
        require(theme == "light" || theme == "dark")
        fun logo(name: String): String =
            Base64.encodeToString(
                instrumentation.context.assets.open("demo/$name.png").use { it.readBytes() },
                Base64.NO_WRAP
            )

        val settings = LocationSettings(context)
        settings.enabled = false
        settings.locationEngine = LocationEngine.FRAMEWORK
        settings.inside = emptySet()
        fun sample(
            id: String, brand: String, cardName: String, color: Long, format: BarcodeType,
            value: String, logoName: String, offset: Double
        ) = Card(
            id, brand, cardName, R.drawable.ic_card, color.toInt(),
            if (id == "readme-cycles") 0xFF173437.toInt() else -1,
            format, value, logoImage = logo(logoName),
            stores = listOf(
                StoreLocation("$id-centre", "$brand · Centrum",
                    if (id == "readme-cycles" || id == "readme-hardware") 55.6981 + offset else 55.6761 + offset,
                    12.5683, 900f),
                StoreLocation("$id-harbour", "$brand · Havnen", 55.7071 + offset, 12.5713, 900f)
            )
        )
        val cards = listOf(
            sample("readme-brutto", "Brutto", "Minus", 0xFF652A7C, BarcodeType.QR_CODE,
                "BRUTTO-0000-0001", "brutto-minus", 0.0000),
            sample("readme-kiosk", "Krumle Kiosk", "Snack Pass", 0xFF7A355F, BarcodeType.CODE_128,
                "0000123400005678", "krumle-kiosk", 0.0001),
            sample("readme-furniture", "Flat Packed Furniture", "Assembler", 0xFFB94D32, BarcodeType.QR_CODE,
                "FLAT-0000-0042", "flat-packed-furniture", 0.0002),
            sample("readme-cycles", "Skæv Skrue Cykler", "Service Pass", 0xFFE9BB42, BarcodeType.QR_CODE,
                "SKAEV-0000-0024", "skaev-skrue-cykler", 0.0003),
            sample("readme-hardware", "Bits & Bobs", "Toolbox", 0xFF243F5A, BarcodeType.PDF_417,
                "BITS-0000-0007", "bits-and-bobs-hardware", 0.0004)
        )
        val repository = LocalCardRepository.get(context)
        cards.forEach { repository.saveCard(it) }

        val output = File(requireNotNull(context.getExternalFilesDir(null)), "readme-screenshots/$theme")
        output.mkdirs()
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) {
                runCatching {
                    compose.onNodeWithTag("wallet_card_readme-hardware").assertExists()
                }.isSuccess
            }
            capture("wallet")
        }
        if (theme == "light") {
            listOf(
                "qr" to cards[0], "code-128" to cards[1], "pdf417" to cards[4]
            ).forEach { (name, card) ->
                ActivityScenario.launch<CardActivity>(
                    CardActivity.intent(context, card).putExtra(CardActivity.OPEN_ENLARGED, true)
                ).use {
                    compose.onNodeWithText(card.rawValue).assertExists()
                    capture(name)
                }
            }
        }
        settings.enabled = true
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(30_000) {
                runCatching { compose.onNodeWithTag("nearby_card_deck").assertExists() }.isSuccess
                    && settings.lastNearbyUpdateAtMillis > 0
            }
            capture("nearby-first")
        }
        // Freeze nearby updates so only the featured card appears on the lock screen.
        settings.enabled = false
        val notifications = CardNotificationManager(context)
        notifications.hide()
        check(CompanionNotificationClient(context).status().ready)
        check(notifications.show(cards[0]))
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }
        val lockDisabled = shell("locksettings get-disabled")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceSecure) { "Screenshot emulator must not have a PIN" }
        try {
            shell("locksettings set-disabled false")
            shell("input keyevent KEYCODE_SLEEP")
            compose.waitUntil(8_000) { keyguard.isKeyguardLocked }
            shell("input keyevent KEYCODE_WAKEUP")
            compose.waitUntil(15_000) {
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    runCatching {
                        com.google.zxing.MultiFormatReader().decode(
                            com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(
                                com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                            ))
                        ).text == cards[0].rawValue
                    }.getOrDefault(false)
                } finally {
                    bitmap.recycle()
                }
            }
            instrumentation.uiAutomation.waitForIdle(1_000, 8_000)
            check(keyguard.isKeyguardLocked)
            capture("lock-screen")
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("locksettings set-disabled $lockDisabled")
            notifications.hide()
        }
    }

}
