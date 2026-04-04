package com.codeextractor.app.domain.avatar.audio

import com.codeextractor.app.domain.avatar.AudioFeatures
import com.codeextractor.app.domain.avatar.EmotionalProsody
import kotlin.math.max
import kotlin.math.min

class ProsodyTracker {

    private var smoothPitch = 0f
    private var prevRms = 0f
    private var silenceMs = 0L

    fun update(features: AudioFeatures, prosody: EmotionalProsody, dtMs: Long) {
        val dt = dtMs / 1000f

        if (!features.hasVoice) {
            silenceMs += dtMs
            // Decay emotions during silence
            prosody.arousal = max(0f, prosody.arousal - dt * 1.5f)
            prosody.valence = prosody.valence * (1f - dt * 0.8f)
            // Thoughtfulness rises during pauses (AI is "thinking")
            if (silenceMs > 600) {
                prosody.thoughtfulness = min(1f, prosody.thoughtfulness + dt * 0.5f)
            }
            prevRms = 0f
            return
        }

        silenceMs = 0
        prosody.thoughtfulness = max(0f, prosody.thoughtfulness - dt * 3f)

        // Arousal from energy spikes
        val spike = max(0f, (features.rms - prevRms) * 8f)
        prosody.arousal = if (spike > 0.1f) {
            min(1f, prosody.arousal + spike * dt * 12f)
        } else {
            max(0f, prosody.arousal - dt * 1.2f)
        }

        // Pitch tracking
        if (features.pitch > 0f) {
            if (smoothPitch == 0f) smoothPitch = features.pitch
            smoothPitch += (features.pitch - smoothPitch) * 10f * dt

            // High pitch + high freq energy = positive (smile/laugh)
            // Low pitch + high arousal = negative (anger)
            val target = when {
                smoothPitch > 220f && features.energyHigh > 0.3f -> 0.7f
                smoothPitch > 200f && prosody.arousal > 0.4f -> 0.4f
                prosody.arousal > 0.6f && smoothPitch < 130f -> -0.5f
                else -> 0f
            }
            prosody.valence += (target - prosody.valence) * 2f * dt
        }

        prevRms = features.rms
        prosody.valence = prosody.valence.coerceIn(-1f, 1f)
        prosody.arousal = prosody.arousal.coerceIn(0f, 1f)
    }
}