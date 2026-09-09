package com.waveq.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS_FILE = "waveq_mesh_onboarding"
private const val KEY_INTRO_SHOWN = "intro_shown"
private const val KEY_HOME_CARD_DISMISSED = "home_card_dismissed"
private const val KEY_ALERT_SETUP_SHOWN = "alert_setup_shown"

/**
 * Tracks whether the user has been told what the mesh is, and whether they have
 * dismissed the reminder that it is off.
 *
 * The mesh runs as a foreground service and therefore only starts when the user
 * asks for it. That is the right default for a radio and a persistent
 * notification, but it left a fresh install silently receiving nothing: no
 * alerts, no SOS beacons, no indication that anything was missing, until the
 * user happened to find a switch three taps deep in Mesh Channels. In a
 * life-safety app that is a failure mode, not a preference.
 *
 * Plain SharedPreferences with StateFlows on top - these are two booleans read
 * during composition, and DataStore's async load would flash the intro on every
 * cold start before the stored value arrived.
 */
object MeshOnboarding {

    @Volatile private var prefs: android.content.SharedPreferences? = null

    private val _introShown = MutableStateFlow(false)
    /** True once the one-screen explainer has been shown and answered. */
    val introShown: StateFlow<Boolean> = _introShown

    private val _homeCardDismissed = MutableStateFlow(false)
    val homeCardDismissed: StateFlow<Boolean> = _homeCardDismissed

    private val _alertSetupShown = MutableStateFlow(false)
    /** True once the Alert delivery step of onboarding has been shown and answered. */
    val alertSetupShown: StateFlow<Boolean> = _alertSetupShown

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        prefs = store
        _introShown.value = store.getBoolean(KEY_INTRO_SHOWN, false)
        _homeCardDismissed.value = store.getBoolean(KEY_HOME_CARD_DISMISSED, false)
        _alertSetupShown.value = store.getBoolean(KEY_ALERT_SETUP_SHOWN, false)
    }

    fun markAlertSetupShown(context: Context) {
        init(context)
        _alertSetupShown.value = true
        prefs?.edit()?.putBoolean(KEY_ALERT_SETUP_SHOWN, true)?.apply()
    }

    fun markIntroShown(context: Context) {
        init(context)
        _introShown.value = true
        prefs?.edit()?.putBoolean(KEY_INTRO_SHOWN, true)?.apply()
    }

    fun dismissHomeCard(context: Context) {
        init(context)
        _homeCardDismissed.value = true
        prefs?.edit()?.putBoolean(KEY_HOME_CARD_DISMISSED, true)?.apply()
    }

    /**
     * Brings the reminder back.
     *
     * Called when the mesh is switched on: if it is ever turned off again the
     * user should be told, rather than having permanently silenced the warning
     * with a dismissal they made months earlier.
     */
    fun resetHomeCard(context: Context) {
        init(context)
        _homeCardDismissed.value = false
        prefs?.edit()?.putBoolean(KEY_HOME_CARD_DISMISSED, false)?.apply()
    }
}
