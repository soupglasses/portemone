// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.notifications.integrations

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import dev.finnes.portemone.AppDiagnostics
import dev.finnes.portemone.Card
import dev.finnes.portemone.CardNotificationStyle
import dev.finnes.portemone.LocationSettings
import dev.finnes.portemone.NotificationCardRenderer

/** A blocked notification must never be rerouted to evade the user's notification settings. */
internal enum class PostResult { POSTED, BLOCKED, UNAVAILABLE }
internal enum class BackendAvailability { READY, BLOCKED, UNAVAILABLE }

/** Rendering quirks and notification ownership belong to the backend, never the geofence layer. */
internal interface NotificationBackend {
    val ownerPackage: String
    fun availability(): BackendAvailability
    fun post(card: Card, tag: String?, id: Int, standard: () -> Notification): PostResult
    fun cancel(tag: String?, id: Int)
}

internal class StandardNotificationBackend(private val context: Context) : NotificationBackend {
    override val ownerPackage = context.packageName
    private val manager = context.getSystemService(NotificationManager::class.java)
    override fun availability() = if (manager.areNotificationsEnabled()) BackendAvailability.READY else BackendAvailability.BLOCKED
    override fun post(card: Card, tag: String?, id: Int, standard: () -> Notification): PostResult {
        manager.notify(tag, id, standard())
        return PostResult.POSTED
    }
    override fun cancel(tag: String?, id: Int) = manager.cancel(tag, id)
}

internal class SamsungNotificationBackend(private val context: Context) : NotificationBackend {
    override val ownerPackage = context.packageName
    private val manager = context.getSystemService(NotificationManager::class.java)
    override fun availability() = when {
        !SamsungNowBar.availableForTrial -> BackendAvailability.UNAVAILABLE
        !manager.areNotificationsEnabled() -> BackendAvailability.BLOCKED
        else -> BackendAvailability.READY
    }
    override fun post(card: Card, tag: String?, id: Int, standard: () -> Notification): PostResult {
        if (!SamsungNowBar.availableForTrial) return PostResult.UNAVAILABLE
        val presented = SamsungNowBar.apply(context, card, standard(),
            NotificationCardRenderer.createSamsung(context, card))
        manager.notify(tag, id, presented)
        return PostResult.POSTED
    }
    override fun cancel(tag: String?, id: Int) = manager.cancel(tag, id)
}

internal class PixelCompatibilityBackend(context: Context) : NotificationBackend {
    override val ownerPackage = PixelNotificationCompat.PACKAGE
    private val helper = PixelNotificationCompat(context)
    override fun availability(): BackendAvailability = helper.status().let {
        when {
            it.blocked -> BackendAvailability.BLOCKED
            it.ready -> BackendAvailability.READY
            else -> BackendAvailability.UNAVAILABLE
        }
    }
    override fun post(card: Card, tag: String?, id: Int, standard: () -> Notification): PostResult =
        when (helper.show(card, tag, id)) {
            true -> PostResult.POSTED
            false -> PostResult.BLOCKED
            null -> PostResult.UNAVAILABLE
        }
    override fun cancel(tag: String?, id: Int) = helper.cancel(tag, id)
}

/** Shared routing, fallback and cleanup for built-in OEM and optional companion integrations. */
internal class NotificationBackends(private val context: Context) {
    private val standard = StandardNotificationBackend(context)
    private val samsung = SamsungNotificationBackend(context)
    private val pixel = PixelCompatibilityBackend(context)

    data class State(val ownerPackage: String, val blocked: Boolean)

    private fun selected(): NotificationBackend = when (notificationHost(
        LocationSettings(context).notificationPresentation, SamsungNowBar.availableForTrial, SamsungNowBar.isSamsung)) {
        NotificationHost.STANDARD -> standard
        NotificationHost.SAMSUNG -> samsung
        NotificationHost.HELPER -> pixel
    }

    fun state(): State {
        val backend = selected()
        val availability = backend.availability()
        return State(if (availability == BackendAvailability.READY) backend.ownerPackage else standard.ownerPackage,
            availability == BackendAvailability.BLOCKED)
    }

    fun post(card: Card, tag: String?, id: Int, style: CardNotificationStyle,
             standardNotification: () -> Notification): Boolean {
        val selected = selected()
        var used: NotificationBackend = selected
        var result = selected.post(card, tag, id, standardNotification)
        if (result == PostResult.UNAVAILABLE) {
            used = standard
            result = standard.post(card, tag, id, standardNotification)
        }
        if (result == PostResult.BLOCKED) cancel(tag, id)
        else {
            // A helper toggle or revoked delegation must not leave duplicate card faces.
            if (used.ownerPackage != standard.ownerPackage) standard.cancel(tag, id)
            if (used.ownerPackage != pixel.ownerPackage) pixel.cancel(tag, id)
        }
        AppDiagnostics.event("Notification", "${card.id}: backend=${used.javaClass.simpleName}, result=$result")
        return result == PostResult.POSTED
    }

    fun cancel(tag: String?, id: Int) {
        standard.cancel(tag, id)
        pixel.cancel(tag, id)
    }

    fun cancel(posted: StatusBarNotification) {
        when (posted.packageName) {
            standard.ownerPackage -> standard.cancel(posted.tag, posted.id)
            pixel.ownerPackage -> pixel.cancel(posted.tag, posted.id)
        }
    }
}
