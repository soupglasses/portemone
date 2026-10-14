// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import dev.finnes.portemone.diagnostics.AppDiagnostics

object BarcodeRenderer {
    data class FittedQr(val bitmap: Bitmap, val symbolWidth: Int)

    fun fitQr(value: String, maxSize: Int, encoding: String = "UTF-8"): FittedQr {
        val modules = MultiFormatWriter().encode(
            value, BarcodeFormat.QR_CODE, 1, 1,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to encoding)
        ).width
        val scale = maxSize / modules
        require(scale > 0) { "QR code does not fit this display area" }
        // Avoid ZXing's extra centering padding: the caption should follow the
        // required four-module quiet zone, not a larger square image placeholder.
        return FittedQr(
            render(value, BarcodeType.QR_CODE, modules * scale, modules * scale, encoding),
            (modules - 8) * scale
        )
    }

    fun fitSquare(card: Card, maxSize: Int): FittedQr {
        if (card.barcodeType == BarcodeType.QR_CODE) return fitQr(
            card.rawValue,
            maxSize,
            card.barcodeEncoding
        )
        if (card.barcodeType == BarcodeType.DATA_MATRIX) {
            val bitmap =
                render(card.rawValue, card.barcodeType, maxSize, maxSize, card.barcodeEncoding)
            return FittedQr(bitmap, bitmap.width)
        }
        val modules = MultiFormatWriter().encode(
            card.rawValue, card.barcodeType.format, 1, 1,
            mapOf(EncodeHintType.CHARACTER_SET to card.barcodeEncoding)
        ).width
        // Aztec has no required quiet zone; provide four white modules for reliable presentation.
        val scale = maxSize / (modules + 8)
        require(scale > 0) { "Code does not fit this display area" }
        val symbol = render(
            card.rawValue,
            card.barcodeType,
            modules * scale,
            modules * scale,
            card.barcodeEncoding
        )
        val bitmap = Bitmap.createBitmap(
            (modules + 8) * scale,
            (modules + 8) * scale,
            Bitmap.Config.ARGB_8888
        ).apply {
            eraseColor(Color.WHITE); density = Bitmap.DENSITY_NONE
        }
        android.graphics.Canvas(bitmap)
            .drawBitmap(symbol, (4 * scale).toFloat(), (4 * scale).toFloat(), null)
        symbol.recycle()
        return FittedQr(bitmap, modules * scale)
    }

    fun render(
        value: String,
        type: BarcodeType,
        width: Int,
        height: Int,
        encoding: String = "UTF-8"
    ): Bitmap {
        AppDiagnostics.event("Barcode", "Rendering $type at ${width}x${height} pixels")
        require(type != BarcodeType.NONE && value.isNotEmpty() && width > 0 && height > 0)
        type.validateValue(value)
        val format = type.format
        val pdf = type == BarcodeType.PDF_417
        val dataMatrix = type == BarcodeType.DATA_MATRIX
        // PDF417Writer adds its margin after choosing a scale. Fit its native
        // modules ourselves so the final symbol and quiet zone stay in bounds.
        val matrix = MultiFormatWriter().encode(
            value,
            format,
            if (pdf || dataMatrix) 1 else width,
            if (pdf || dataMatrix) 1 else height,
            barcodeEncodingHints(encoding) + (EncodeHintType.MARGIN to when {
                pdf -> 0
                type.square -> 4
                type in listOf(BarcodeType.EAN_13, BarcodeType.UPC_A, BarcodeType.UPC_E) -> 22
                else -> 20
            })
        )
        // DataMatrixWriter ignores MARGIN, so reserve its one-module quiet zone here.
        val scale = when {
            pdf -> minOf((width - 40) / matrix.width, (height - 40) / matrix.height)
            dataMatrix -> minOf(width / (matrix.width + 2), height / (matrix.height + 2))
            else -> 1
        }
        require(scale > 0 && matrix.width * scale <= width && matrix.height * scale <= height) {
            "Barcode does not fit this display area"
        }
        val left = (width - matrix.width * scale) / 2
        val top = (height - matrix.height * scale) / 2
        val pixels = IntArray(width * height) { i ->
            val px = i % width - left
            val py = i / width - top
            if (px in 0 until matrix.width * scale && py in 0 until matrix.height * scale &&
                matrix[px / scale, py / scale]
            ) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
        }
    }
}
