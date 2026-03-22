package com.codeextractor.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

class AudioFocusManager(
    context: Context,
    private val onFocusLost: () -> Unit
) {
    private val TAG = "AudioFocusManager"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost (change=$change)")
                onFocusLost()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus regained")
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener(focusChangeListener)
        .build()

    fun requestFocus(): Boolean {
        val result = audioManager.requestAudioFocus(focusRequest)
        Log.d(TAG, "requestAudioFocus result=$result")
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        Log.d(TAG, "abandonAudioFocusRequest")
    }
}