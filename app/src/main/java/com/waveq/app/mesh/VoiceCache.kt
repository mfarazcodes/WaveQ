package com.waveq.app.mesh

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "VoiceCache"

/** Received clips older than this are deleted. Matches the message store's TTL. */
private const val VOICE_TTL_MS = MESSAGE_STORE_TTL_MS

/** Absolute ceiling regardless of age, oldest deleted first. */
private const val VOICE_CACHE_MAX_BYTES = 32L * 1024 * 1024

/**
 * Housekeeping for voice clips written to the cache directory.
 *
 * Nothing deleted them. Every received voice note was written to `cacheDir` and
 * left there forever, and every recording made on this device likewise - the
 * recorder writes `voice_<uuid>.m4a` before sending and never cleans up. Over a
 * long deployment, which is exactly the scenario this app is built for, that
 * grows without bound.
 *
 * Two rules, both needed: a TTL matching how long the mesh store keeps the
 * message a clip belongs to, and a size ceiling for the case where a burst
 * arrives well inside the TTL.
 */
object VoiceCache {

    fun purge(context: Context, now: Long = System.currentTimeMillis()) {
        val cacheDir = context.applicationContext.cacheDir ?: return
        val clips = cacheDir.listFiles { file -> file.isFile && file.name.startsWith("voice_") }
            ?: return

        var deleted = 0
        val surviving = mutableListOf<File>()
        for (clip in clips) {
            if (now - clip.lastModified() > VOICE_TTL_MS) {
                if (clip.delete()) deleted++
            } else {
                surviving.add(clip)
            }
        }

        // Still over the ceiling: drop the oldest survivors until it fits.
        var total = surviving.sumOf { it.length() }
        if (total > VOICE_CACHE_MAX_BYTES) {
            surviving.sortBy { it.lastModified() }
            for (clip in surviving) {
                if (total <= VOICE_CACHE_MAX_BYTES) break
                val size = clip.length()
                if (clip.delete()) {
                    total -= size
                    deleted++
                }
            }
        }
        if (deleted > 0) Log.i(TAG, "purged $deleted voice clip(s)")
    }

    /** Deletes one clip - used when its message is dropped from the UI. */
    fun delete(file: File?) {
        if (file == null) return
        runCatching { if (file.exists()) file.delete() }
    }
}
