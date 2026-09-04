package com.waveq.app.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var currentAudioFile: File? = null
        private set

    fun startRecording(): Boolean {
        return try {
            val outputDir = context.cacheDir
            currentAudioFile = File.createTempFile("voice_note_", ".m4a", outputDir)

            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(currentAudioFile?.absolutePath)
                prepare()
                start()
            }
            true
        } catch (e: IOException) {
            Log.e("VoiceRecorder", "Failed to start recording", e)
            recorder?.release()
            recorder = null
            false
        }
    }

    fun stopRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Error stopping recording", e)
        } finally {
            recorder = null
        }
    }

    fun clear() {
        currentAudioFile?.delete()
        currentAudioFile = null
    }
}