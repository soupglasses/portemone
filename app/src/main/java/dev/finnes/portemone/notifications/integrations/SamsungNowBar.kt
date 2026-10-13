// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.notifications.integrations

import dev.finnes.portemone.AppDiagnostics
import dev.finnes.portemone.Card
import dev.finnes.portemone.R

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews

/**
 * Experimental Samsung host integration, separate from Android's public Live Updates API.
 * These undocumented extras can be ignored by One UI. They do not prove Now Bar placement.
 * Samsung's expanded slot is distinct from both bigContentView and the compact Now Bar.
 * Protocol references (OEM internals, not a supported third-party SDK):
 * https://github.com/488315/android_samsung_frameworks_base/blob/d7c13fe69a1ad46f8dff0254f1abd32596fef5cd/packages/SystemUI/src/com/android/systemui/statusbar/phone/ongoingactivity/OngoingActivityData.java
 * https://github.com/488315/samsung_framework/blob/30cd25b68f67be792ab78fb46fb9f63af0964522/framework/sources/android/app/Notification.java
 */
object SamsungNowBar {
    val isSamsung: Boolean
        get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    val availableForTrial: Boolean
        get() = isSamsung && Build.VERSION.SDK_INT >= 35

    fun extras(context: Context, card: Card, expanded: RemoteViews): Bundle {
        val title = "${card.brandName} ${card.cardName}"
        val prefix = "android.ongoingActivityNoti."
        val icon = Icon.createWithResource(context, R.drawable.ic_card)
        return Bundle().apply {
            putInt(prefix + "style", 1)
            putString(prefix + "primaryInfo", title)
            putString(prefix + "nowbarPrimaryInfo", title)
            putString(prefix + "secondaryInfo", "")
            putString(prefix + "nowbarSecondaryInfo", "")
            putString(prefix + "chipExpandedText", title)
            putInt(prefix + "chipBgColor", card.brandColor)
            putParcelable(prefix + "chipIcon", icon)
            putParcelable(prefix + "nowbarIcon", icon)
            // System UI substitutes its own expanded layout for bigContentView.
            // Supply the actual expanded-card slot, including the Fold cover screen.
            putParcelable(prefix + "expandedRemoteView", expanded)
            putParcelable(prefix + "subScreenExpandedRemoteView", expanded)
            // Leave nowbarRemoteView and chronometerRemoteView absent: they can
            // replace the compact name. No QR belongs in either shared/compact slot.
        }
    }

    @Suppress("DEPRECATION")
    fun apply(context: Context, card: Card, fallback: Notification, expanded: RemoteViews): Notification {
        if (!availableForTrial) return fallback
        val samsungExtras = extras(context, card, expanded)
        fallback.extras.putAll(samsungExtras)
        return try {
            // Reflect only public methods on the OEM class. Some builds hide it;
            // do not bypass hidden-API enforcement or sacrifice the QR on failure.
            val type = Class.forName("android.app.Notification\$OngoingActivityStyle")
            val nativeStyle = type.getConstructor().newInstance() as Notification.Style
            val title = "${card.brandName} ${card.cardName}"
            val icon = Icon.createWithResource(context, R.drawable.ic_card)
            type.getMethod("setPrimaryInfo", CharSequence::class.java).invoke(nativeStyle, title)
            type.getMethod("setSecondaryInfo", CharSequence::class.java).invoke(nativeStyle, "")
            type.getMethod("setExpandedChipText", CharSequence::class.java).invoke(nativeStyle, title)
            type.getMethod("setChipIcon", Icon::class.java).invoke(nativeStyle, icon)
            type.getMethod("setCardIcon", Icon::class.java).invoke(nativeStyle, icon)
            type.getMethod("setChipBackground", Int::class.javaPrimitiveType).invoke(nativeStyle, card.brandColor)
            type.getMethod("setCardBackground", Int::class.javaPrimitiveType).invoke(nativeStyle, card.brandColor)
            type.getMethod("setCustomExpandedCardView", RemoteViews::class.java).invoke(nativeStyle, expanded)
            val fullExpansion = type.getField("NOW_BAR_EXPANDABLE_TYPE_FULL").getInt(null)
            type.getMethod("setExpandedNowBarView", RemoteViews::class.java, Int::class.javaPrimitiveType)
                .invoke(nativeStyle, expanded, fullExpansion)
            // This style owns its actions and otherwise overwrites the builder's list.
            fallback.actions?.forEach { action ->
                type.getMethod("addAction", Notification.Action::class.java).invoke(nativeStyle, action)
            }
            Notification.Builder.recoverBuilder(context, fallback.clone())
                .setStyle(nativeStyle)
                .build()
                .also {
                    it.extras.putAll(samsungExtras)
                    // The OEM style doesn't implement Android's custom-view factory.
                    // Keep the original layouts available to ordinary notification hosts.
                    it.contentView = fallback.contentView
                    it.bigContentView = fallback.bigContentView
                    AppDiagnostics.event("NowBar", "Native OngoingActivityStyle; expanded QR fields supplied")
                    Log.d("PortemoneNowBar", "Native OngoingActivityStyle with separate expanded QR")
                }
        } catch (error: ReflectiveOperationException) {
            AppDiagnostics.event("NowBar", "Native style unavailable; using separate Samsung expanded-card fields", error)
            Log.i("PortemoneNowBar", "Native style unavailable; using Samsung expanded-card fields", error)
            fallback
        } catch (error: RuntimeException) {
            AppDiagnostics.event("NowBar", "Native style rejected; using separate Samsung expanded-card fields", error)
            Log.i("PortemoneNowBar", "Native style rejected; using Samsung expanded-card fields", error)
            fallback
        } catch (error: LinkageError) {
            AppDiagnostics.event("NowBar", "Native style incompatible; using separate Samsung expanded-card fields", error)
            Log.i("PortemoneNowBar", "Native style incompatible; using Samsung expanded-card fields", error)
            fallback
        }
    }
}
