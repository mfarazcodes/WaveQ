package com.waveq.app

import android.app.Application
import com.waveq.app.alerts.AppForegroundState
import com.waveq.app.alerts.SirenPlayer
import com.waveq.app.ui.components.OsmConfig

/**
 * Exists for one reason: process-global initialisation that must happen before
 * any screen runs.
 *
 * osmdroid's [org.osmdroid.config.Configuration] is a static singleton, and it
 * was previously configured inside EvacuationMapScreen's `AndroidView` factory -
 * so the tile user agent, base path and cache path were only ever set if that
 * one screen happened to be opened first. Any MapView constructed before it (and
 * after this change there are several) would run with osmdroid's default
 * "osmdroid" user agent, which OpenStreetMap's tile servers reject outright.
 */
class WaveQApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must be registered before any Activity resumes: alert delivery uses it
        // to decide whether a direct takeover launch can work at all.
        AppForegroundState.register(this)
        OsmConfig.init(this)
        // If a previous process died while the siren was sounding, the user's
        // alarm volume is still pinned at maximum. Put it back.
        SirenPlayer.restorePendingVolume(this)
    }
}
