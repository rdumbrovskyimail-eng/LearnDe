package com.codeextractor.app.domain.avatar.linguistics

import com.codeextractor.app.domain.avatar.AudioFeatures
import com.codeextractor.app.domain.avatar.LinguisticState
import com.codeextractor.app.domain.avatar.VisemeGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * TextAudioPacer v2 — Speech-Session-Aware Synchronizer
 *
 * Между первым AudioChunk и TurnComplete действует «сессия речи».
 * Внутри сессии лента ВСЕГДА продвигается:
 *   - Голос активен: rate = 1.0x (с drift correction)
 *   - Пауза < 800мс: rate = 0.7x (естественная пауза вдоха)
 *   - Пауза > 800мс: rate = 0.4x (думает/лагает, но не стоит)
 *   - TurnComplete + лента пуста + тишина: сессия завершается
 *
 * Confidence НЕ падает ниже 0.35 во время сессии.
 */
class TextAudioPacer(private val ribbon: PhoneticRibbon) {

    companion object {
        private const val DRIFT_GAIN        = 0.0005f
        private const val MAX_DRIFT_MS      = 2000L
        private const val RATE_MIN          = 0.35f
        private const val RATE_MAX          = 2.0f

        private const val SHORT_PAUSE_MS    = 800L
        private const val SHORT_PAUSE_RATE  = 0.7f
        private const val LONG_PAUSE_RATE   = 0.4f
        private const val NO_SESSION_RATE   = 0.15f

        private const val CONF_RISE_SPEED   = 0.12f
        private const val CONF_DECAY_SPEED  = 0.008f
        private const val CONF_EMPTY_DECAY  = 0.04f
        private const val CONF_NO_SESSION   = 0.06f

        private const val VOICE_RMS_THR     = 0.025f
    }

    private var accumMs = 0f
    private var currentDurationMs = 80f
    private var playbackRate = 1.0f

    private var audioElapsedMs = 0L
    private var textConsumedMs = 0L

    @Volatile
    private var sessionActive = false
    private var turnEnding = false
    private var silenceDurationMs = 0L
    private var lastVoiceTimeMs = 0L
    private var totalSessionMs = 0L

    private var confidence = 0f

    private var progress = 0f
    private var wasTransition = false

    val linguisticState = LinguisticState()

    // ══════════════════════════════════════════════════════════════════════

    fun onAudioChunk(pcmBytes: Int, sampleRate: Int = 24_000) {
        val samples = pcmBytes / 2
        val durationMs = (samples * 1000L) / sampleRate
        audioElapsedMs += durationMs

        if (!sessionActive) {
            sessionActive = true
            turnEnding = false
            silenceDurationMs = 0L
            totalSessionMs = 0L
        }
    }

    fun tick(dtMs: Long, audio: AudioFeatures) {
        val dt = dtMs.coerceIn(1, 32)
        wasTransition = false

        if (sessionActive) {
            totalSessionMs += dt
            if (turnEnding && !ribbon.hasReadable && silenceDurationMs > 500L) {
                sessionActive = false
                turnEnding = false
            }
        }

        val hasVoiceNow = audio.hasVoice && audio.rms > VOICE_RMS_THR
        if (hasVoiceNow) {
            silenceDurationMs = 0L
            lastVoiceTimeMs = totalSessionMs
        } else {
            silenceDurationMs += dt
        }

        if (!ribbon.hasReadable) {
            confidence = max(0f, confidence - CONF_EMPTY_DECAY)
            updateLinguisticState()
            return
        }

        if (hasVoiceNow && ribbon.hasReadable) {
            confidence = min(1f, confidence + CONF_RISE_SPEED)
        } else if (sessionActive) {
            confidence = max(0.35f, confidence - CONF_DECAY_SPEED)
        } else {
            confidence = max(0f, confidence - CONF_NO_SESSION)
        }

        computePlaybackRate(audio, dt)

        val effectiveRate = if (sessionActive || hasVoiceNow) {
            playbackRate
        } else {
            playbackRate * NO_SESSION_RATE
        }

        currentDurationMs = ribbon.peekDurationMs(0).toFloat().coerceAtLeast(15f)
        accumMs += dt * effectiveRate

        if (accumMs >= currentDurationMs) {
            textConsumedMs += currentDurationMs.toLong()
            accumMs -= currentDurationMs
            accumMs = max(0f, accumMs)
            ribbon.advance()
            wasTransition = true
            currentDurationMs = ribbon.peekDurationMs(0).toFloat().coerceAtLeast(15f)
        }

        progress = (accumMs / currentDurationMs).coerceIn(0f, 1f)
        updateLinguisticState()
    }

    fun markTurnEnding() {
        turnEnding = true
    }

    fun onTurnBoundary() {
        sessionActive = false
        turnEnding = false
        audioElapsedMs = 0L
        textConsumedMs = 0L
        accumMs = 0f
        playbackRate = 1.0f
        silenceDurationMs = 0L
        totalSessionMs = 0L
        progress = 0f
        wasTransition = false
    }

    fun reset() {
        onTurnBoundary()
        confidence = 0f
        lastVoiceTimeMs = 0L
        linguisticState.reset()
    }

    // ══════════════════════════════════════════════════════════════════════

    private fun computePlaybackRate(audio: AudioFeatures, dt: Long) {
        val baseRate = when {
            audio.hasVoice && audio.rms > VOICE_RMS_THR -> 1.0f
            silenceDurationMs < SHORT_PAUSE_MS          -> SHORT_PAUSE_RATE
            else                                         -> LONG_PAUSE_RATE
        }

        val drift = audioElapsedMs - textConsumedMs
        if (abs(drift) > MAX_DRIFT_MS) {
            playbackRate = if (drift > 0) RATE_MAX else RATE_MIN
            return
        }

        val driftCorrection = drift * DRIFT_GAIN
        var targetRate = baseRate + driftCorrection

        if (audio.spectralFlux > 0.25f) {
            targetRate *= 1.2f
        }

        playbackRate += (targetRate - playbackRate) * 0.20f
        playbackRate = playbackRate.coerceIn(RATE_MIN, RATE_MAX)
    }

    private fun updateLinguisticState() {
        linguisticState.update(
            gate = ribbon.peekGate(0),
            nextG = ribbon.peekGate(1),
            profile = ribbon.peekProfile(0),
            nextProfile = ribbon.peekProfile(1),
            prog = progress,
            transition = wasTransition,
            punct = ribbon.peekPunctuation(12),
            confidence = confidence,
        )
    }
}
