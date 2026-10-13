// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

internal data class CardColorSuggestion(val color: Int, val tonal: Boolean = false)

internal object CardAppearance {
    private val paletteCache = android.util.LruCache<String, List<CardColorSuggestion>>(4)

    fun suggestions(image: String): List<CardColorSuggestion> {
        paletteCache.get(image)?.let { return it }
        val original = PassImages.decode(image)
        val sample = Bitmap.createScaledBitmap(original, 96, 96, true)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        if (sample !== original) sample.recycle()
        original.recycle()
        // Transparent padding is not a brand color. Quantization and scoring belong to Palette.
        val visible =
            pixels.filter { Color.alpha(it) >= 128 }.map { it or Color.BLACK }.toIntArray()
        if (visible.isEmpty()) return emptyList()
        val bitmap = Bitmap.createBitmap(visible, visible.size, 1, Bitmap.Config.ARGB_8888)
        val palette = try {
            Palette.from(bitmap).resizeBitmapArea(-1).clearFilters().generate()
        } finally {
            bitmap.recycle()
        }
        val colors = (listOfNotNull(
            palette.dominantSwatch,
            palette.vibrantSwatch,
            palette.darkVibrantSwatch,
            palette.lightVibrantSwatch,
            palette.mutedSwatch,
            palette.darkMutedSwatch,
            palette.lightMutedSwatch
        ) +
                palette.swatches.sortedByDescending { it.population }).map { it.rgb }.distinct()
            .take(5)
        if (colors.isEmpty()) return emptyList()
        val result = colors.map { CardColorSuggestion(it) }.toMutableList()
        for (amount in listOf(.2f, .4f, .6f, .8f)) {
            for (target in listOf(Color.WHITE, Color.BLACK)) {
                val variant = ColorUtils.blendARGB(colors.first(), target, amount)
                if (result.size < 5 && result.none { it.color == variant }) result += CardColorSuggestion(
                    variant,
                    tonal = true
                )
            }
        }
        paletteCache.put(image, result)
        return result
    }

    fun foreground(color: Int): Int =
        if (ColorUtils.calculateContrast(Color.WHITE, color) >= 4.5) Color.WHITE else Color.BLACK

    fun shade(color: Int): Int {
        val target = if (foreground(color) == Color.WHITE) Color.BLACK else Color.WHITE
        val shaded = ColorUtils.blendARGB(color, target, .18f)
        return if (shaded == color) ColorUtils.blendARGB(
            color,
            if (target == Color.BLACK) Color.WHITE else Color.BLACK,
            .08f
        ) else shaded
    }

    fun gradient(color: Int): Bitmap =
        Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawRect(0f, 0f, 128f, 128f, Paint().apply {
                shader =
                    LinearGradient(0f, 0f, 128f, 128f, color, shade(color), Shader.TileMode.CLAMP)
            })
        }
}
