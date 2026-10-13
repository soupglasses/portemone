// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.launch

class WalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.initialize(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            .launch {
                val coordinator = GeofenceCoordinator(this@WalletApplication)
                runCatching {
                    coordinator.reconcileCardValidity()
                }.onFailure {
                    AppDiagnostics.event("Geofence", "Startup recovery needs a retry", it)
                    if (LocationSettings(this@WalletApplication).enabled) RegionRefreshJob.schedule(
                        this@WalletApplication
                    )
                }
                if (LocationSettings(this@WalletApplication).hasPendingCardChanges()) {
                    runCatching { CardChangeWork.enqueue(this@WalletApplication) }
                        .onFailure {
                            AppDiagnostics.event(
                                "Cards",
                                "Could not schedule saved card recovery",
                                it
                            )
                        }
                }
            }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppDiagnostics.event("Crash", "Uncaught exception on ${thread.name}", error)
            runCatching { AppDiagnostics.flush() }
            previousHandler?.uncaughtException(thread, error)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) =
                AppDiagnostics.event("Screen", "Opened ${activity.javaClass.simpleName}")

            override fun onActivityStopped(activity: Activity) =
                AppDiagnostics.event("Screen", "Stopped ${activity.javaClass.simpleName}")

            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
