// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.Base64

internal object PassImages {
    // Fit visible artwork, not transparent export margins; keep stored image bytes intact.
    fun decodeLogo(image: String): Bitmap {
        val bitmap = decode(image)
        val row = IntArray(bitmap.width)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) if (row[x] ushr 24 != 0) {
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = y
            }
        }
        if (right < left) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    fun decode(image: String): Bitmap {
        val bytes = Base64.getDecoder().decode(image)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
}
