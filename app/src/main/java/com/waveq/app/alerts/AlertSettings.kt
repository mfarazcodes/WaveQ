package com.waveq.app.alerts

import android.content.Context

private const val PREFS_FILE = "waveq_alert_settings"
private const val KEY_SIREN_ENABLED = "siren_enabled"
private const val KEY_FULLSCREEN_ENABLED = "fullscreen_enabled"
private const val KEY_FULLSCREEN_SETUP_CARD_DISMISSED = "fullscreen_setup_card_dismissed"

/**
 * User-configurable critical-alert behaviour, set from the Admin Panel
 * Settings tab. Backed by plain SharedPreferences (like [com.waveq.app.mesh.MeshSession]'s
 * device prefs) rather than a ViewModel, since the trigger path
 * ([CriticalAlertTrigger]) needs to read these from places with no ViewModel
 * in scope (a mesh callback thread, a notification action).
 */
object AlertSettings {

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isSirenEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SIREN_ENABLED, true)

    fun setSirenEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIREN_ENABLED, enabled).apply()
    }

    fun isFullScreenEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_FULLSCREEN_ENABLED, true)

    fun setFullScreenEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULLSCREEN_ENABLED, enabled).apply()
    }

    /** Whether the user has dismissed the Home-screen "enable full-screen alerts" setup card. */
    fun isFullScreenSetupCardDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULLSCREEN_SETUP_CARD_DISMISSED, false)

    fun dismissFullScreenSetupCard(context: Context) {
        prefs(context).edit().putBoolean(KEY_FULLSCREEN_SETUP_CARD_DISMISSED, true).apply()
    }
}
