// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.SizeF
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import kotlin.math.min
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.roundToInt

class CardWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, CardWidgetProvider::class.java))
                .forEach { id ->
                    // A host/rendering failure must not interrupt geofence registration or exit handling.
                    runCatching { update(context, manager, id) }
                        .onFailure { AppDiagnostics.event("Widget", "Could not update widget $id", it) }
                }
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val settings = LocationSettings(context)
            val options = manager.getAppWidgetOptions(id)
            val card = settings.activeCards().firstOrNull()
            AppDiagnostics.event("Widget", "Update $id: ${card?.id ?: "hidden outside stores"}")
            manager.updateAppWidget(id, if (card == null) RemoteViews(context.packageName, R.layout.card_widget_empty)
                else CardWidgetRenderer.renderForHost(context, card, options, true))
        }
    }
}

object CardWidgetRenderer {
    fun renderForHost(context: Context, card: Card, options: Bundle, nearby: Boolean): RemoteViews {
        if (!nearby) return RemoteViews(context.packageName, R.layout.card_widget_empty)
        @Suppress("DEPRECATION")
        val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            .orEmpty().filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
            .distinct().take(16)
        if (sizes.isNotEmpty()) {
            val views = sizes.associateWith { render(context, card, it, true) }
            return if (views.size == 1) views.values.first() else RemoteViews(views)
        }
        // Legacy hosts report portrait width/landscape height as the minima. Using both
        // minima for every orientation leaves a narrow barcode inside a wide widget.
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260).coerceAtLeast(1)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180).coerceAtLeast(1)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth).coerceAtLeast(minWidth)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight).coerceAtLeast(minHeight)
        val portrait = render(context, card, SizeF(minWidth.toFloat(), maxHeight.toFloat()), true)
        if (minWidth == maxWidth && minHeight == maxHeight) return portrait
        val landscape = render(context, card, SizeF(maxWidth.toFloat(), minHeight.toFloat()), true)
        return RemoteViews(landscape, portrait)
    }

    fun render(context: Context, card: Card, size: SizeF, nearby: Boolean): RemoteViews {
        // A separate layout removes even the old bitmap and PendingIntent from the host.
        if (!nearby) return RemoteViews(context.packageName, R.layout.card_widget_empty)
        val views = RemoteViews(context.packageName, R.layout.card_widget)
        views.setTextViewText(R.id.widget_title, "${card.brandName} ${card.cardName}")
        // Keep a single white scanning surface; use branding in the heading with readable contrast.
        val headingColor = if (ColorUtils.calculateContrast(card.brandColor or android.graphics.Color.BLACK,
                android.graphics.Color.WHITE) >= 4.5) card.brandColor or android.graphics.Color.BLACK
            else android.graphics.Color.BLACK
        views.setTextColor(R.id.widget_title, headingColor)
        val open = PendingIntent.getActivity(context, 2,
            CardActivity.intent(context, card),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(android.R.id.background, open)
        var rendered = false
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        fun textHeight(sp: Float, minimumDp: Int, typeface: Typeface): Int {
            val paint = Paint().apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
                this.typeface = typeface
            }
            return max(px(minimumDp), ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt())
        }
        val titleHeight = textHeight(16f, 24, Typeface.DEFAULT_BOLD)
        views.setViewLayoutHeight(R.id.widget_title, titleHeight.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        // ZXing already reserves the horizontal quiet zones. Extra nested padding or a
        // pixel-width cap would shrink the bars, especially on the Fold's inner display.
        val width = (size.width * density).roundToInt()
        val availableHeight = (size.height * density).roundToInt() - 2 * px(12) - titleHeight - px(8)
        val qr = card.barcodeType.square
        // QR already contains white quiet zones on all four sides; no additional inset is needed.
        val height = availableHeight - if (qr) 0 else 2 * px(4)
        val codeWidth = if (qr) min(width, height) else width
        // Limit vertical bitmap memory in tall slots without ever limiting barcode width.
        val codeHeight = if (qr) codeWidth else min(height, px(160))
        if (!qr || codeWidth >= (80 * density).roundToInt()) {
            val bitmap = runCatching { BarcodeRenderer.render(card.rawValue, card.barcodeType, codeWidth, codeHeight, card.barcodeEncoding) }.getOrNull()
            if (bitmap != null) {
                // Black and white are exact in RGB_565. Halving the parcel's bitmap memory
                // leaves room for the Fold's multiple full-width layouts without scaling.
                val compactBitmap = bitmap.copy(Bitmap.Config.RGB_565, false).apply {
                    this.density = Bitmap.DENSITY_NONE
                }
                views.setImageViewBitmap(R.id.widget_code, compactBitmap)
                views.setContentDescription(R.id.widget_code, context.getString(R.string.membership_code_description, card.brandName, context.getString(card.barcodeType.labelRes)))
                rendered = true
            }
        }
        views.setViewVisibility(R.id.widget_code, if (rendered) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_message, if (rendered) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.widget_message, if (card.barcodeType == BarcodeType.NONE) context.getString(R.string.plain_card_tap_details) else context.getString(R.string.enlarge_widget_to_show_code))
        return views
    }
}
