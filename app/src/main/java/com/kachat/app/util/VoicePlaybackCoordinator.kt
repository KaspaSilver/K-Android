package com.kachat.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * One voice note plays at a time, and the audio is given back when it stops (iOS f954738
 * `VoicePlaybackCoordinator`).
 *
 * Every voice bubble owns its own player, so without this two notes could play over each other,
 * and music another app was playing never came back after a note. A player calls [willStart]
 * right before it plays (the one playing before is stopped, and transient audio focus is taken,
 * Android's counterpart of iOS's `.playback` session) and [didStop] when it pauses, stops or
 * finishes (the focus is abandoned, so the other app's audio resumes, as iOS's
 * `.notifyOthersOnDeactivation`).
 *
 * The room recorder ([com.kachat.app.services.VoiceRecorderService]) takes its own focus while it
 * records: [recordingWillStart] stops any note playing, and nothing plays until [recordingDidEnd].
 *
 * Synchronized: the recorder may call from a background thread.
 */
object VoicePlaybackCoordinator {
    private var currentOwner: Any? = null
    private var stopCurrent: (() -> Unit)? = null
    private var isRecording = false
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Stops whatever note is playing and takes the audio for [owner]'s playback. False while a
     * recording is in progress: the caller doesn't play. [stop] pauses [owner] when another note
     * starts.
     */
    @Synchronized
    fun willStart(context: Context, owner: Any, stop: () -> Unit): Boolean {
        if (isRecording) return false
        if (currentOwner !== owner) stopPrevious()
        takeFocus(context.applicationContext)
        currentOwner = owner
        stopCurrent = stop
        return true
    }

    /** [owner] paused, stopped or finished. If it was the one playing, the audio is given back so
     *  other apps' audio can resume. */
    @Synchronized
    fun didStop(owner: Any) {
        if (currentOwner !== owner) return
        currentOwner = null
        stopCurrent = null
        if (!isRecording) releaseFocus()
    }

    /** The recorder is about to take the audio: any note playing stops first. */
    @Synchronized
    fun recordingWillStart() {
        stopPrevious()
        releaseFocus()
        isRecording = true
    }

    /** The recorder gave the audio back. */
    @Synchronized
    fun recordingDidEnd() {
        isRecording = false
    }

    private fun stopPrevious() {
        // Cleared before the call, so the stopped player's own didStop doesn't give back the
        // audio the next player is about to use.
        val previous = stopCurrent
        currentOwner = null
        stopCurrent = null
        runCatching { previous?.invoke() }
            .onFailure { Log.w("VoicePlayback", "Couldn't stop the note playing", it) }
    }

    private fun takeFocus(context: Context) {
        if (focusRequest != null) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(
                { change ->
                    // a call or another app took the audio: the note pauses
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        stopPrevious()
                        releaseFocus()
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
            .build()
        focusRequest = request
        appAudioManager = manager
        runCatching { manager.requestAudioFocus(request) }
    }

    private var appAudioManager: AudioManager? = null

    private fun releaseFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { appAudioManager?.abandonAudioFocusRequest(request) }
    }
}
