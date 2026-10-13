// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BarcodeFormatsRenderingTest {
    @Test
    fun everyFormatDecodesAtCardAndExpandedSizes() {
        for (type in BarcodeType.entries.filter { it != BarcodeType.NONE }) {
            val value = barcodeFixture(type)
            for (width in listOf(320, 960)) {
                val bitmap =
                    BarcodeRenderer.render(value, type, width, if (type.square) width else 200)
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue(
                        "$type must have pure black and white pixels",
                        pixels.all { it == Color.BLACK || it == Color.WHITE })
                    if (type != BarcodeType.AZTEC) {
                        assertTrue(
                            "$type left quiet zone",
                            (0 until bitmap.height).all { pixels[it * bitmap.width] == Color.WHITE })
                        assertTrue(
                            "$type right quiet zone",
                            (0 until bitmap.height).all { pixels[(it + 1) * bitmap.width - 1] == Color.WHITE })
                    }
                    val result = MultiFormatReader().decode(
                        BinaryBitmap(
                            HybridBinarizer(
                                RGBLuminanceSource(
                                    bitmap.width,
                                    bitmap.height,
                                    pixels
                                )
                            )
                        ),
                        barcodeDecodeHints(type)
                    )
                    assertEquals("$type at $width pixels", value, result.text)
                    assertEquals(type.format, result.barcodeFormat)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }
}
