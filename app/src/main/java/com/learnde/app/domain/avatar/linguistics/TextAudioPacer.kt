package com.learnde.app.domain.avatar.linguistics

import com.learnde.app.domain.avatar.AudioFeatures
import com.learnde.app.domain.avatar.LinguisticState
import com.learnde.app.domain.avatar.SpeechFlowController
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * TextAudioPacer v3 — Cyclic Flow Pacer
 *
 * ═══════════════════════════════════════════════════════════════════
 *  КОНЦА НЕ СУЩЕСТВУЕТ. ВСЁ ЦИКЛИЧНО.
 * ═══════════════════════════════════════════════════════════════════
 *
 * Правила:
 *   1. Если есть текст в ленте → ВСЕГДА продвигаем (скорость варьируется)
 *   2. Если нет текста → confidence плавно падает, но НЕ мгновенно
 *   3. Скорость определяется: audioActivity + pauseDepth + drift
 *   4. Паузы (запятая, точка) = замедление, НЕ остановка
 *   5. «Конец» наступает ТОЛЬКО когда лента пуста И speechMomentum < 0.05
 *
 * Зависит от SpeechFlowController (читает его выходы).
 */
class TextAudioPacer(private val ribbon: PhoneticRibbon) {

    companion object {
        private const val DRIFT_GAIN    = 0.0005f
        private const val MAX_DRIFT_MS  = 2500L
        private const val RATE_MIN      = 0.25f
        private const val RATE_MAX      = 1.6f

        // Скорость по уровню активности
        private const val RATE_FULL_VOICE = 1.0f    // голос активен
        private const val RATE_LIGHT_PAUSE = 0.75f  // пауза < 600мс
        private const val RATE_DEEP_PAUSE  = 0.45f  // пауза > 1500мс
        private const val RATE_NO_MOMENTUM = 0.10f  // momentum < 0.05 (реально конец)

        // Confidence
        private const val CONF_MAX      = 1.0f
        private const val CONF_RISE     = 0.15f     // per frame
        private const val CONF_HOLD     = 0.40f     // минимум пока speechMomentum > 0.3
        private const val CONF_SLOW_DECAY = 0.005f  // per frame при momentum > 0.3
        private const val CONF_FAST_DECAY = 0.03f   // per frame при пустой ленте
    }

    private var accumMs = 0f
    private var currentDurationMs = 80f
    private var playbackRate = 1.0f

    private var audioElapsedMs = 0L
    private var textConsumedMs = 0L

    private var confidence = 0f
    private var progress = 0f
    private var wasTransition = false

    val linguisticState = LinguisticState()

    // ══════════════════════════════════════════════════════════════════════

    fun onAudioChunk(pcmBytes: Int, sampleRate: Int = 24_000) {
        audioElapsedMs += (pcmBytes / 2 * 1000L) / sampleRate
    }

    /**
     * Главный тик. Зависит от SpeechFlowController.
     */
    fun tick(dtMs: Long, audio: AudioFeatures, flow: SpeechFlowController) {
        val dt = dtMs.coerceIn(1, 32)
        wasTransition = false

        // ── Confidence management ─────────────────────────────────────────
        updateConfidence(flow)

        // ── Нет текста — просто обновляем state ───────────────────────────
        if (!ribbon.hasReadable) {
            updateLinguisticState()
            return
        }

        // ── Вычисляем скорость ────────────────────────────────────────────
        computeRate(audio, flow, dt)

        // ── ВСЕГДА продвигаем ленту (пока есть текст) ─────────────────────
        currentDurationMs = ribbon.peekDurationMs(0).toFloat().coerceAtLeast(15f)
        accumMs += dt * playbackRate

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

    fun resetClocks() {
        audioElapsedMs = 0L
        textConsumedMs = 0L
        accumMs = 0f
        playbackRate = 1.0f
    }

    fun reset() {
        resetClocks()
        confidence = 0f
        progress = 0f
        wasTransition = false
        linguisticState.reset()
    }

    // ══════════════════════════════════════════════════════════════════════

    private fun updateConfidence(flow: SpeechFlowController) {
        when {
            // Есть текст + momentum > 0.3 → confidence растёт / держится
            ribbon.hasReadable && flow.speechMomentum > 0.3f -> {
                if (flow.audioActivity > 0.1f) {
                    confidence = min(CONF_MAX, confidence + CONF_RISE)
                } else {
                    confidence = max(CONF_HOLD, confidence - CONF_SLOW_DECAY)
                }
            }
            // Есть текст, но momentum низкий → медленный decay
            ribbon.hasReadable -> {
                confidence = max(0.15f, confidence - CONF_SLOW_DECAY)
            }
            // Нет текста, но momentum есть → средний decay
            flow.speechMomentum > 0.1f -> {
                confidence = max(0f, confidence - CONF_SLOW_DECAY * 2f)
            }
            // Нет ничего → быстрый decay
            else -> {
                confidence = max(0f, confidence - CONF_FAST_DECAY)
            }
        }
    }

    private fun computeRate(audio: AudioFeatures, flow: SpeechFlowController, dt: Long) {
        // ── Base rate from pause depth ─────────────────────────────────────
        val baseRate = when {
            flow.speechMomentum < 0.05f         -> RATE_NO_MOMENTUM
            flow.pauseDepth < 0.3f              -> RATE_FULL_VOICE
            flow.pauseDepth < 0.7f              -> RATE_LIGHT_PAUSE
            else                                 -> RATE_DEEP_PAUSE
        }

        // ── Drift correction ──────────────────────────────────────────────
        val drift = audioElapsedMs - textConsumedMs
        val driftCorrection = if (abs(drift) > MAX_DRIFT_MS) {
            if (drift > 0) 0.5f else -0.2f  // Более мягкая хард-коррекция
        } else {
            drift * DRIFT_GAIN
        }

        var targetRate = baseRate + driftCorrection

        // Spectral flux boost
        if (audio.spectralFlux > 0.20f) {
            targetRate *= 1.15f
        }

        // Smooth
        playbackRate += (targetRate - playbackRate) * 0.18f
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
