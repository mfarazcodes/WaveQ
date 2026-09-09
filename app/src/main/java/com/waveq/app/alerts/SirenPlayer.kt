package com.waveq.app.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SirenPlayer"
private const val SAMPLE_RATE = 44100
private const val TONE_LOW_HZ = 600.0
private const val TONE_HIGH_HZ = 1200.0
private const val AMPLITUDE_SCALE = 0.8
private const val AUTO_STOP_MS = 60_000L
private val VIBRATION_PATTERN = longArrayOf(0, 500, 300)

private const val PREFS_FILE = "waveq_siren_state"
private const val KEY_SAVED_ALARM_VOLUME = "saved_alarm_volume"

/**
 * Looping alarm-stream siren + repeating vibration for CRITICAL mesh alerts.
 *
 * The tone is synthesized in-process with [AudioTrack] rather than a bundled
 * audio file: one second of a seamless 600Hz-1200Hz-600Hz sweep, looped via
 * AudioTrack's native loop points. The sweep's average frequency (900Hz) times
 * its 1s duration is an exact multiple of 2*pi, so phase is continuous across
 * the loop boundary - no click at the seam. Plays on STREAM_ALARM specifically
 * (not notification/media) so it sounds even when the ringer is silenced.
 *
 * A process-wide singleton (not tied to any Activity) so both the full-screen
 * takeover's two buttons and the trigger path that started it can stop the
 * same siren regardless of which one is currently in scope.
 */
object SirenPlayer {
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var vibrator: Vibrator? = null
    private var previousAlarmVolume: Int? = null
    /** Held only so stop() can clear the persisted volume it saved in start(). */
    private var managerContext: Context? = null
    private var autoStopJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val isPlaying: Boolean get() = audioTrack != null

    /**
     * Restores an alarm volume left maxed by a previous process that died mid-siren.
     *
     * Called from the Application. Without it, a crash or an OS kill while the
     * siren was sounding left the user's alarm volume pinned at maximum
     * permanently, with nothing in the app aware it had ever changed it.
     */
    @Synchronized
    fun restorePendingVolume(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val saved = prefs.getInt(KEY_SAVED_ALARM_VOLUME, -1)
        if (saved < 0) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) setAlarmVolumeSafely(am, saved)
        prefs.edit().remove(KEY_SAVED_ALARM_VOLUME).apply()
    }

    /**
     * Synchronized because this is reached from at least three threads:
     * CriticalAlertTrigger's Dispatchers.Default scope, the takeover Activity's
     * main thread, and the auto-stop coroutine. Two concurrent starts could both
     * pass the null check, leaking an AudioTrack and - worse - overwriting the
     * saved volume with the already-maxed value, so "restoring" pinned the
     * user's alarm volume at maximum for good.
     */
    @Synchronized
    fun start(context: Context) {
        if (audioTrack != null) {
            Log.i(ALERT_PATH_TAG, "siren already sounding - start ignored")
            return
        }

        val appContext = context.applicationContext
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am

        // Captured only when there is nothing already saved, so a second start
        // cannot overwrite the real pre-siren volume with the maxed one.
        if (previousAlarmVolume == null) {
            val current = am.getStreamVolume(AudioManager.STREAM_ALARM)
            previousAlarmVolume = current
            // Persisted before the change, so a process death mid-siren is
            // recoverable on next launch.
            appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putInt(KEY_SAVED_ALARM_VOLUME, current).apply()
            managerContext = appContext
        }
        setAlarmVolumeSafely(am, am.getStreamMaxVolume(AudioManager.STREAM_ALARM))

        try {
            audioTrack = buildSirenTrack().also { it.play() }
        } catch (e: Exception) {
            // AudioTrack construction can fail outright on some devices and
            // configurations. Previously this propagated into a scope with no
            // exception handler; now it is reported and the alert still stands
            // on its notification and takeover.
            Log.e(ALERT_PATH_TAG, "siren AudioTrack failed to start", e)
            audioTrack = null
        }
        vibrator = runCatching {
            vibratorFor(appContext).also {
                it.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
            }
        }.getOrElse {
            Log.w(ALERT_PATH_TAG, "vibration failed", it)
            null
        }
        Log.i(ALERT_PATH_TAG, "siren started (audio=${audioTrack != null})")

        autoStopJob = scope.launch {
            delay(AUTO_STOP_MS)
            Log.i(ALERT_PATH_TAG, "siren auto-stop after ${AUTO_STOP_MS}ms")
            stop()
        }
    }

    /** Stops the tone, the vibration, cancels the auto-stop timer, and restores the pre-siren alarm volume. */
    @Synchronized
    fun stop() {
        autoStopJob?.cancel()
        autoStopJob = null

        // Two concurrent stops could both see a non-null track and call
        // stop()/release() on an already-released one, throwing IllegalState.
        audioTrack?.also {
            runCatching {
                it.stop()
                it.release()
            }
        }
        audioTrack = null

        vibrator?.cancel()
        vibrator = null

        val am = audioManager
        val restoreVolume = previousAlarmVolume
        if (am != null && restoreVolume != null) {
            setAlarmVolumeSafely(am, restoreVolume)
        }
        // The persisted copy exists only to survive a process death mid-siren.
        // A clean stop has just restored the volume, so clear it.
        managerContext?.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            ?.edit()?.remove(KEY_SAVED_ALARM_VOLUME)?.apply()

        audioManager = null
        managerContext = null
        previousAlarmVolume = null
    }

    /**
     * Alarm-stream volume changes throw [SecurityException] when the device is
     * in Do Not Disturb and the app lacks *granted* notification-policy access.
     * ACCESS_NOTIFICATION_POLICY is declared but nothing requests the grant, and
     * DND is precisely when a critical alert must sound - so this path is likely
     * to be hit, and it previously took the process down from inside
     * CriticalAlertTrigger's handler-less scope instead of alerting anyone.
     *
     * Failing to raise the volume is survivable; the siren still plays at
     * whatever the alarm stream is currently set to.
     */
    private fun setAlarmVolumeSafely(manager: AudioManager, volume: Int) {
        try {
            manager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
        } catch (e: SecurityException) {
            Log.w(
                ALERT_PATH_TAG,
                "cannot raise alarm volume (Do Not Disturb without notification-policy access) - " +
                    "the siren still plays at the current level",
                e,
            )
        }
    }

    private fun buildSirenTrack(): AudioTrack {
        val samples = generateSirenWaveform()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(samples.size * 2) // 16-bit PCM = 2 bytes/sample
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.setLoopPoints(0, samples.size, -1) // -1 = loop forever
        return track
    }

    /** One second of PCM samples: frequency rises 600->1200Hz then falls back to 600Hz. */
    private fun generateSirenWaveform(): ShortArray {
        val frameCount = SAMPLE_RATE
        val samples = ShortArray(frameCount)
        val center = (TONE_LOW_HZ + TONE_HIGH_HZ) / 2.0
        val swing = (TONE_HIGH_HZ - TONE_LOW_HZ) / 2.0
        var phase = 0.0
        for (i in 0 until frameCount) {
            val t = i.toDouble() / SAMPLE_RATE
            val instantaneousFreqHz = center - swing * cos(2 * Math.PI * t)
            phase += 2 * Math.PI * instantaneousFreqHz / SAMPLE_RATE
            samples[i] = (sin(phase) * Short.MAX_VALUE * AMPLITUDE_SCALE).toInt().toShort()
        }
        return samples
    }

    private fun vibratorFor(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
