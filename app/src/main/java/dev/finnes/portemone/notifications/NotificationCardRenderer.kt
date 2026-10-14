// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.notifications

import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.widget.RemoteViews
import dev.finnes.portemone.BarcodeRenderer
import dev.finnes.portemone.BarcodeType
import dev.finnes.portemone.Card
import dev.finnes.portemone.CardActivity
import dev.finnes.portemone.CardAppearance
import dev.finnes.portemone.CardFieldSection
import dev.finnes.portemone.PassImages
import dev.finnes.portemone.R
import dev.finnes.portemone.diagnostics.AppDiagnostics
import kotlin.math.roundToInt

/** Landscape membership faces. Only the machine-readable code is a bitmap. */
object NotificationCardRenderer {
    const val ASPECT_RATIO = 85.60f / 53.98f

    fun create(
        context: Context, card: Card, widthPx: Int, cornerRadiusDp: Float = 12f,
        fitHostWidth: Boolean = false, bindOpenAction: Boolean = true,
        useBitmapIcon: Boolean = false
    ): RemoteViews {
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = (dp * density).roundToInt()
        val height = (widthPx / ASPECT_RATIO).roundToInt()
        val qr = card.barcodeType.square
        // Reserve a small caption below the complete bitmap, including its quiet zone.
        // Reuse some vertical padding for CODE_128 so its full width is unchanged.
        val codeWidth = if (qr) height - px(30f) else widthPx - px(24f)
        val headerHeight = px(56f)
        val codeHeight = if (qr) codeWidth else height - headerHeight - px(32f)
        require(codeWidth > 0 && codeHeight > 0)
        val fittedQr =
            if (qr) runCatching { BarcodeRenderer.fitSquare(card, codeWidth) }.getOrNull() else null
        val rendered =
            if (card.barcodeType == BarcodeType.NONE) null else fittedQr?.bitmap ?: runCatching {
                BarcodeRenderer.render(
                    card.rawValue,
                    card.barcodeType,
                    codeWidth,
                    codeHeight,
                    card.barcodeEncoding
                )
            }.getOrNull()
        val bitmap = rendered ?: android.graphics.Bitmap.createBitmap(
            1,
            1,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        return RemoteViews(
            context.packageName,
            if (qr) R.layout.notification_card_qr else R.layout.notification_card_barcode
        ).apply {
            // Pixel dimensions match the generated bitmap exactly; no ImageView fitting.
            // Samsung shares this view between the shade and a narrower Now Bar popup.
            // The face must follow its parent, otherwise the popup cuts through the corners.
            setViewLayoutWidth(
                R.id.card_face,
                if (fitHostWidth) -1f else widthPx.toFloat(),
                TypedValue.COMPLEX_UNIT_PX
            )
            setViewLayoutHeight(R.id.card_face, height.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            setViewOutlinePreferredRadius(
                R.id.card_face,
                cornerRadiusDp,
                TypedValue.COMPLEX_UNIT_DIP
            )
            setViewLayoutWidth(R.id.barcode, bitmap.width.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            setViewLayoutHeight(R.id.barcode, bitmap.height.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            if (rendered == null) {
                setViewVisibility(R.id.barcode, android.view.View.GONE)
            } else setViewVisibility(R.id.barcode, android.view.View.VISIBLE)
            setTextViewText(
                R.id.card_number,
                if (rendered == null && card.barcodeType != BarcodeType.NONE) context.getString(R.string.open_card_to_scan) else if (card.barcodeType == BarcodeType.NONE) card.fields.firstOrNull { it.section == CardFieldSection.PRIMARY }?.value.orEmpty() else card.rawValue
            )
            if (qr) {
                setViewLayoutWidth(
                    R.id.card_number,
                    (fittedQr?.symbolWidth ?: codeWidth).toFloat(),
                    TypedValue.COMPLEX_UNIT_PX
                )
                setViewPadding(R.id.scan_area, 0, 0, 0, 0)
                setViewLayoutWidth(R.id.scan_area, height.toFloat(), TypedValue.COMPLEX_UNIT_PX)
                setTextViewText(R.id.title, "${card.brandName}\n${card.cardName}")
            } else {
                setViewLayoutHeight(
                    R.id.brand_header,
                    headerHeight.toFloat(),
                    TypedValue.COMPLEX_UNIT_PX
                )
                setTextViewText(R.id.title, "${card.brandName} ${card.cardName}")
            }
            val narrow = widthPx / density < 290f
            setTextViewTextSize(R.id.title, TypedValue.COMPLEX_UNIT_SP, if (narrow) 16f else 19f)
            setContentDescription(R.id.title, "${card.brandName} ${card.cardName}")
            setTextColor(
                R.id.title,
                if (card.source != null && androidx.core.graphics.ColorUtils.calculateContrast(
                        card.accentColor,
                        card.brandColor
                    ) >= 4.5
                ) card.accentColor
                else if (androidx.core.graphics.ColorUtils.calculateContrast(
                        Color.WHITE,
                        card.brandColor
                    ) >= 4.5
                ) Color.WHITE else Color.BLACK
            )
            // Keep the established brand treatment only while its original palette
            // is selected; an edited color must also reach the full card face.
            setViewVisibility(
                R.id.brand_gradient,
                if (card.shaded) android.view.View.VISIBLE else android.view.View.GONE
            )
            if (card.shaded) {
                setImageViewBitmap(R.id.brand_gradient, CardAppearance.gradient(card.brandColor))
                setTextColor(R.id.title, CardAppearance.foreground(card.brandColor))
            }
            setInt(
                R.id.brand_header,
                "setBackgroundColor",
                if (card.shaded) Color.TRANSPARENT else card.brandColor
            )
            if (card.logoImage != null) {
                val logo = PassImages.decodeLogo(card.logoImage)
                val maxHeight = if (narrow || !qr) 36f else 48f
                val maxWidth = if (narrow) 56f else 76f
                val scale = minOf(maxWidth / logo.width, maxHeight / logo.height)
                setImageViewBitmap(R.id.brand_logo, logo)
                setInt(
                    R.id.brand_logo,
                    "setBackgroundColor",
                    Color.TRANSPARENT
                )
                setViewPadding(R.id.brand_logo, 0, 0, 0, 0)
                setViewLayoutWidth(
                    R.id.brand_logo,
                    logo.width * scale,
                    TypedValue.COMPLEX_UNIT_DIP
                )
                setViewLayoutHeight(
                    R.id.brand_logo,
                    logo.height * scale,
                    TypedValue.COMPLEX_UNIT_DIP
                )
            } else {
                setImageViewResource(R.id.brand_logo, card.logo)
                setInt(R.id.brand_logo, "setBackgroundColor", Color.TRANSPARENT)
                setViewPadding(R.id.brand_logo, 0, 0, 0, 0)
                setViewLayoutWidth(
                    R.id.brand_logo,
                    if (narrow) 36f else 48f,
                    TypedValue.COMPLEX_UNIT_DIP
                )
                setViewLayoutHeight(
                    R.id.brand_logo,
                    if (narrow || !qr) 36f else 48f,
                    TypedValue.COMPLEX_UNIT_DIP
                )
            }
            // The optional legacy host has a tall compact view. Bitmap-backed Icons avoid
            // RemoteViews' separate 48dp compact bitmap-cache reduction on Pixel.
            if (useBitmapIcon) setImageViewIcon(
                R.id.barcode,
                android.graphics.drawable.Icon.createWithBitmap(bitmap)
            )
            else setImageViewBitmap(R.id.barcode, bitmap)
            setContentDescription(
                R.id.barcode, context.getString(
                    R.string.membership_code_description,
                    card.brandName, context.getString(card.barcodeType.labelRes)
                )
            )
            if (bindOpenAction) {
                setOnClickPendingIntent(
                    R.id.notification_surface, PendingIntent.getActivity(
                        context,
                        0,
                        CardActivity.scanIntent(context, card),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        }
    }

    fun createSamsung(context: Context, card: Card): RemoteViews {
        // One UI fades notification content separately from its rounded background
        // during swipes. A squarer face exposes bright crescents outside that layer.
        // Match both Samsung hosts; RemoteViews cannot change the host's outline.
        val radius = maxOf(
            samsungDimension(context, "notification_corner_radius", 36f),
            samsungDimension(context, "ongoing_activity_card_bg_radius", 36f)
        )
        val width = samsungWidth(context)
        AppDiagnostics.event(
            "CardLayout",
            "Samsung card ${card.id}: width=${width}px; corner=${radius}dp"
        )
        return create(context, card, width, radius, fitHostWidth = true)
    }

    private fun samsungDimension(context: Context, name: String, fallbackDp: Float): Float =
        runCatching {
            val resources =
                context.packageManager.getResourcesForApplication("com.android.systemui")

            @Suppress("DiscouragedApi")
            val id = resources.getIdentifier(name, "dimen", "com.android.systemui")
            if (id != 0) resources.getDimension(id) / resources.displayMetrics.density else fallbackDp
        }.getOrDefault(fallbackDp)

    /** Samsung supplies an edge-to-edge expanded slot, unlike the ordinary template. */
    fun samsungWidth(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val config = context.resources.configuration
        val padding = samsungDimension(context, "notification_side_paddings", 10f)
        // Bound wide Fold/tablet layouts to the expanded notification height budget.
        val widthDp = (config.screenWidthDp - 2 * padding).coerceIn(220f, 396f)
        return (widthDp * density).roundToInt()
    }
}
