package com.codeextractor.app.domain.avatar.linguistics

import com.codeextractor.app.domain.avatar.AudioFeatures
import com.codeextractor.app.domain.avatar.LinguisticState
import com.codeextractor.app.domain.avatar.VisemeGroup
import kotlin.math.abs
import kotlin.math.max

/**
 * TextAudioPacer — Синхронизатор текстовой ленты и аудио-потока.
 *
 * ПРОБЛЕМА: Текст приходит целыми фразами мгновенно.
 *           Аудио течёт чанками по ~20мс.
 *           Нужно «проматывать» ленту ровно со скоростью речи.
 *
 * РЕШЕНИЕ — Adaptive Rate Control:
 *   1. Каждая фонема имеет estimatedMs из PhonemeData
 *   2. Таймер копит реальное dt и advance() при истечении
 *   3. Audio clock (по размеру PCM чанков) корректирует скорость
 *   4. При тишине — лента замирает (не убегает вперёд)
 *   5. При отставании — ускоряется (до 1.8x)
 *
 * Zero-allocation. Single writer / single reader.
 */
class TextAudioPacer(private val ribbon: PhoneticRibbon) {

    companion object {
        private const val DRIFT_GAIN        = 0.0004f
        private const val MAX_DRIFT_MS      = 1500L
        private const val RATE_MIN          = 0.5f
        private const val RATE_MAX          = 1.8f
        private const val SILENCE_RATE      = 0.3f
        private const val CONFIDENCE_RISE   = 0.08f   // per frame when text+audio active
        private const val CONFIDENCE_DECAY  = 0.03f   // per frame when no text
        private const val SILENCE_RMS_THR   = 0.03f
    }

    // ── Internal clock ────────────────────────────────────────────────────
    private var accumMs = 0f
    private var currentDurationMs = 80f   // duration of current phoneme
    private var playbackRate = 1.0f

    // ── Audio clock (fed by onAudioChunk) ─────────────────────────────────
    private var audioElapsedMs = 0L
    private var textConsumedMs = 0L

    // ── Confidence tracking ───────────────────────────────────────────────
    private var confidence = 0f
    private var silentFrames = 0

    // ── Progress within current phoneme ───────────────────────────────────
    private var progress = 0f
    private var wasTransition = false

    // ── Output state (read by AvatarAnimatorImpl) ─────────────────────────
    val linguisticState = LinguisticState()

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Вызывается при получении AudioChunk от Gemini.
     * Обновляет audio clock для drift correction.
     */
    fun onAudioChunk(pcmBytes: Int, sampleRate: Int = 24_000) {
        val samples = pcmBytes / 2
        audioElapsedMs += (samples * 1000L) / sampleRate
    }

    /**
     * Вызывается каждый кадр аниматора.
     * Продвигает ленту и обновляет LinguisticState.
     */
    fun tick(dtMs: Long, audio: AudioFeatures) {
        val dt = dtMs.coerceIn(1, 32)
        wasTransition = false

        // ── Нет текста в ленте → деградация confidence ────────────────────
        if (!ribbon.hasReadable) {
            confidence = max(0f, confidence - CONFIDENCE_DECAY)
            updateLinguisticState()
            return
        }

        // ── Confidence растёт когда есть и текст и голос ───────────────────
        if (audio.hasVoice && audio.rms > SILENCE_RMS_THR) {
            confidence = (confidence + CONFIDENCE_RISE).coerceAtMost(1f)
            silentFrames = 0
        } else {
            silentFrames++
            if (silentFrames > 5) {
                confidence = max(0.1f, confidence - CONFIDENCE_DECAY * 0.5f)
            }
        }

        // ── Drift correction ──────────────────────────────────────────────
        val drift = audioElapsedMs - textConsumedMs
        if (abs(drift) > MAX_DRIFT_MS) {
            // Катастрофический рассинхрон → hard reset
            playbackRate = 1.0f
            accumMs = 0f
        } else {
            val correction = drift * DRIFT_GAIN
            var targetRate = 1.0f + correction
            // При тишине замедляем
            if (!audio.hasVoice || audio.rms < SILENCE_RMS_THR) {
                targetRate *= SILENCE_RATE
            }
            // При spectralFlux ускоряем (ударные слоги)
            if (audio.spectralFlux > 0.25f && audio.isPlosive) {
                targetRate *= 1.3f
            }
            playbackRate += (targetRate - playbackRate) * 0.15f
            playbackRate = playbackRate.coerceIn(RATE_MIN, RATE_MAX)
        }

        // ── Только если есть голос или текущая фонема — тишина ─────────────
        val currentGate = ribbon.peekGate(0)
        val shouldAdvance = (audio.hasVoice && audio.rms > SILENCE_RMS_THR) ||
                            currentGate == VisemeGroup.SILENCE

        if (shouldAdvance) {
            currentDurationMs = ribbon.peekDurationMs(0).toFloat().coerceAtLeast(15f)
            accumMs += dt * playbackRate

            if (accumMs >= currentDurationMs) {
                textConsumedMs += currentDurationMs.toLong()
                accumMs -= currentDurationMs
                accumMs = max(0f, accumMs)
                ribbon.advance()
                wasTransition = true
                // Update duration for new phoneme
                currentDurationMs = ribbon.peekDurationMs(0).toFloat().coerceAtLeast(15f)
            }

            progress = (accumMs / currentDurationMs).coerceIn(0f, 1f)
        } else {
            progress = (accumMs / currentDurationMs).coerceIn(0f, 1f)
        }

        updateLinguisticState()
    }

    /** Вызывается при barge-in или turn boundary */
    fun onTurnBoundary() {
        audioElapsedMs = 0L
        textConsumedMs = 0L
        accumMs = 0f
        playbackRate = 1.0f
        silentFrames = 0
        progress = 0f
        wasTransition = false
        // confidence НЕ сбрасываем — пусть плавно деградирует
    }

    fun reset() {
        onTurnBoundary()
        confidence = 0f
        linguisticState.reset()
    }

    // ══════════════════════════════════════════════════════════════════════

    private fun updateLinguisticState() {
        val gate = ribbon.peekGate(0)
        val nextGate = ribbon.peekGate(1)
        val profile = ribbon.peekProfile(0)
        val nextProfile = ribbon.peekProfile(1)
        val punct = ribbon.peekPunctuation(12)

        linguisticState.update(
            gate = gate,
            nextG = nextGate,
            profile = profile,
            nextProfile = nextProfile,
            prog = progress,
            transition = wasTransition,
            punct = punct,
            confidence = confidence,
        )
    }
}