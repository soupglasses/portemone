// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import kotlin.math.min

/** Rasterize again after resizing: scaling a previously rendered code can blur its modules. */
class FloatingBarcodeView(context: Context, private val card: Card) : View(context) {
    private var bitmap: Bitmap? = null
    private val pixels = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val message = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }

    init {
        setBackgroundColor(Color.WHITE)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.membership_code_description, card.brandName, context.getString(card.barcodeType.labelRes))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Padding is outside ZXing's own quiet zone and remains pure white.
        val inset = (8 * resources.displayMetrics.density).toInt()
        val availableWidth = w - 2 * inset
        val availableHeight = h - 2 * inset
        val width = if (card.barcodeType.square)
            min(availableWidth, availableHeight) else availableWidth
        val height = if (card.barcodeType.square) width else availableHeight
        bitmap = runCatching { BarcodeRenderer.render(card.rawValue, card.barcodeType, width, height, card.barcodeEncoding) }.getOrNull()
        contentDescription = if (card.barcodeType == BarcodeType.NONE) context.getString(R.string.plain_card_open_details) else if (bitmap == null) context.getString(R.string.code_does_not_fit_open_card)
            else context.getString(R.string.membership_code_description, card.brandName, context.getString(card.barcodeType.labelRes))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val code = bitmap
        if (code != null) {
            // Integer placement and the point overload avoid interpolation and density scaling.
            canvas.drawBitmap(code, ((width - code.width) / 2).toFloat(),
                ((height - code.height) / 2).toFloat(), pixels)
        } else {
            canvas.drawText(if (card.barcodeType == BarcodeType.NONE) context.getString(R.string.plain_card_no_barcode) else context.getString(R.string.open_fullscreen_card), width / 2f, height / 2f, message)
        }
    }
}
