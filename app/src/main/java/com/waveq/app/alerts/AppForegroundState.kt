package com.waveq.app.alerts

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether any Activity of this app is currently resumed.
 *
 * Needed because a direct `startActivity` from a background coroutine is
 * *silently ignored* on Android 10+ - the platform logs a background-activity-
 * launch block and returns normally, so there is no exception to catch and no
 * way to tell from the call site that nothing happened. Alert delivery was
 * relying on that call as its primary mechanism.
 *
 * With this, the direct launch is attempted only when it can actually work, and
 * the full-screen intent plus the notification carry the alert otherwise.
 */
object AppForegroundState {

    @Volatile
    var isForeground: Boolean = false
        private set

    private var resumedCount = 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumedCount++
                    isForeground = true
                }

                override fun onActivityPaused(activity: Activity) {
                    resumedCount = (resumedCount - 1).coerceAtLeast(0)
                    isForeground = resumedCount > 0
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
