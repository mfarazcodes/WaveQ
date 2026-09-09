package com.waveq.app.mesh

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.util.UUID

private const val TAG = "VoiceRecorder"
private const val MAX_DURATION_MS = 15_000
private const val AUDIO_BITRATE = 16_000 // low bitrate mono, keeps clips under the Nearby BYTES payload ceiling
private const val SAMPLE_RATE = 16_000

/**
 * Minimal voice-note recorder. Recordings are capped at ~15s and encoded at a
 * low mono bitrate so an encrypted clip reliably fits under Nearby Connections'
 * practical BYTES payload limit - required because outbound voice messages are
 * sent via Payload.fromBytes, never fromFile (see MeshManager/VoiceRecorder
 * usage in MeshViewModel).
 */
class VoiceRecorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(context: Context): File {
        val file = File(context.cacheDir, "voice_${UUID.randomUUID()}.m4a")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(SAMPLE_RATE)
            setAudioEncodingBitRate(AUDIO_BITRATE)
            setMaxDuration(MAX_DURATION_MS)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mediaRecorder
        outputFile = file
        return file
    }

    /**
     * Stops and releases the recorder. Returns the finished file, or null if
     * the recording could not be finalised - most commonly because
     * MediaRecorder had already stopped itself at [MAX_DURATION_MS], or
     * because it was stopped before any frames were written. Both throw
     * IllegalStateException/RuntimeException from stop(), and neither is
     * exceptional enough to crash the app mid-conversation.
     *
     * The recorder is always released, and a half-written file is deleted:
     * when stop() fails the MP4 container was never finalised, so the file is
     * unplayable and must not be sent.
     */
    fun stopRecording(): File? {
        val file = outputFile
        outputFile = null
        val mediaRecorder = recorder
        recorder = null

        if (file == null || mediaRecorder == null) return null

        return try {
            mediaRecorder.stop()
            file
        } catch (e: RuntimeException) {
            // Covers IllegalStateException; MediaRecorder.stop() is documented
            // to throw a bare RuntimeException when no valid data was recorded.
            Log.w(TAG, "recording could not be finalised", e)
            file.delete()
            null
        } finally {
            mediaRecorder.release()
        }
    }
}