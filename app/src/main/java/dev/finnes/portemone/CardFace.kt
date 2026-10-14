// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.finnes.portemone.notifications.NotificationCardRenderer

/** Share the notification's native face; carousel pages retain their actual layout while moving. */
@Composable
fun CardFace(
    card: Card,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
    onLogoClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    BoxWithConstraints(modifier.aspectRatio(NotificationCardRenderer.ASPECT_RATIO)) {
        val width = constraints.maxWidth
        if (width > 0) {
            // The active image is rendered at final pixels and never resized or filtered.
            val face = remember(card, width, configuration) {
                NotificationCardRenderer.create(context, card, width, bindOpenAction = false)
            }
            key(card.barcodeType) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { CardFaceHost(it).apply { bind(face, selected, onLogoClick) } },
                    update = { it.bind(face, selected, onLogoClick) })
            }
        }
    }
}

private class CardFaceHost(context: Context) : FrameLayout(context) {
    private var boundFace: RemoteViews? = null
    private lateinit var code: ImageView

    fun bind(face: RemoteViews, selected: Boolean, onLogoClick: (() -> Unit)?) {
        val changed = boundFace !== face
        if (changed) {
            if (childCount == 0) addView(face.apply(context, this))
            else face.reapply(context, getChildAt(0))
            code = findViewById(R.id.barcode)
            boundFace = face
        }
        findViewById<ImageView>(R.id.brand_logo).apply {
            // Even setOnClickListener(null) makes a View clickable. Repeatedly
            // toggling it can clear native focus and reenter pager measurement
            // while Compose is still applying the current card update.
            if (onLogoClick != null) {
                setOnClickListener { onLogoClick() }
            } else if (hasOnClickListeners()) {
                setOnClickListener(null)
            }
            if (isClickable != (onLogoClick != null)) isClickable = onLogoClick != null
            importantForAccessibility =
                if (onLogoClick != null) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription =
                if (onLogoClick != null) context.getString(R.string.change_logo) else null
        }
        if (changed || code.isActivated != selected) {
            // Selection only affects accessibility. Keep the same sharp bitmap
            // throughout the gesture, without replacing or filtering its pixels.
            code.isActivated = selected
            code.importantForAccessibility =
                if (selected) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
}
