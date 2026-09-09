package com.waveq.app.mesh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS_FILE = "waveq_sos_triage"
private const val KEY_PINNED = "pinned_beacon_ids"
private const val KEY_DISMISSED = "dismissed_beacon_ids"

/**
 * This device's private view of other people's SOS beacons.
 *
 * Purely local, and that is the whole point. Pinning a beacon marks it as one
 * you are responding to; dismissing hides it from your list. Neither does
 * anything to the beacon, to the sender, or to any other device - the beacon
 * keeps repeating, other phones keep showing it, and nothing is transmitted
 * about your choice. The UI states that explicitly, because a "dismiss" button
 * in an emergency app that silently cancelled someone else's call for help
 * would be catastrophic.
 *
 * Backed by plain SharedPreferences rather than a ViewModel because both the SOS
 * screen and the full-screen takeover Activity write to it.
 */
object SosTriage {

    @Volatile private var prefs: android.content.SharedPreferences? = null

    private val _pinned = MutableStateFlow<Set<String>>(emptySet())
    val pinned: StateFlow<Set<String>> = _pinned

    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())
    val dismissed: StateFlow<Set<String>> = _dismissed

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        prefs = store
        _pinned.value = store.getStringSet(KEY_PINNED, emptySet())?.toSet().orEmpty()
        _dismissed.value = store.getStringSet(KEY_DISMISSED, emptySet())?.toSet().orEmpty()
    }

    fun setPinned(context: Context, beaconId: String, pinned: Boolean) {
        init(context)
        val updated = if (pinned) _pinned.value + beaconId else _pinned.value - beaconId
        _pinned.value = updated
        // Pinning something un-dismisses it: you cannot be both responding to a
        // beacon and hiding it.
        if (pinned && beaconId in _dismissed.value) setDismissed(context, beaconId, false)
        persist(KEY_PINNED, updated)
    }

    fun setDismissed(context: Context, beaconId: String, dismissed: Boolean) {
        init(context)
        val updated = if (dismissed) _dismissed.value + beaconId else _dismissed.value - beaconId
        _dismissed.value = updated
        persist(KEY_DISMISSED, updated)
    }

    fun isPinned(beaconId: String): Boolean = beaconId in _pinned.value

    private fun persist(key: String, value: Set<String>) {
        prefs?.edit()?.putStringSet(key, value)?.apply()
    }
}
