package com.waveq.app.mesh

import android.media.MediaPlayer
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "VoicePlayer"

/** Which clip is playing, and how far through it is. */
data class VoicePlaybackState(
    val messageId: String? = null,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
) {
    fun isPlaying(id: String): Boolean = messageId == id
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Plays received voice notes. One clip at a time, process-wide.
 *
 * Voice notes could be recorded, encrypted, sent, relayed and written to disk,
 * and then rendered as the literal text "Voice message" - there was no player
 * anywhere in the app. This is that player.
 *
 * A single shared instance rather than one per composable, because the
 * requirement is that only one clip sounds at a time: starting a second clip has
 * to stop the first, which needs one owner. [MediaPlayer] is released on
 * completion, on error, on stop, and when the chat screen leaves composition.
 */
object VoicePlayer {

    private var player: MediaPlayer? = null

    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state

    /** Starts [file], stopping whatever was playing. Returns false if it could not be opened. */
    @Synchronized
    fun play(messageId: String, file: File): Boolean {
        stop()
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "voice clip missing or empty: ${file.name}")
            return false
        }
        return try {
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "playback error ($what/$extra)")
                    stop()
                    true
                }
                prepare()
                start()
            }
            player = mediaPlayer
            _state.value = VoicePlaybackState(
                messageId = messageId,
                positionMs = 0,
                durationMs = mediaPlayer.duration.coerceAtLeast(0),
            )
            true
        } catch (e: Exception) {
            // A truncated or unfinalised MP4 - a clip whose sender's recorder
            // failed to stop cleanly - throws here rather than playing silence.
            Log.w(TAG, "could not play ${file.name}", e)
            releasePlayer()
            _state.value = VoicePlaybackState()
            false
        }
    }

    @Synchronized
    fun stop() {
        releasePlayer()
        _state.value = VoicePlaybackState()
    }

    /** Called from the UI's progress ticker while something is playing. */
    @Synchronized
    fun refreshPosition() {
        val mediaPlayer = player ?: return
        val current = _state.value
        if (current.messageId == null) return
        _state.value = current.copy(
            positionMs = runCatching { mediaPlayer.currentPosition }.getOrDefault(current.positionMs),
        )
    }

    private fun releasePlayer() {
        player?.let { mediaPlayer ->
            runCatching {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
            }
            runCatching { mediaPlayer.release() }
        }
        player = null
    }
}
