package com.codeextractor.app.domain.avatar.audio

import com.codeextractor.app.domain.avatar.AudioFeatures
import com.codeextractor.app.domain.avatar.EmotionalProsody
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ProsodyTracker v3 — объединение лучших подходов.
 *
 * - Baseline pitch adaptation (Gemini): адаптируется к голосу Gemini,
 *   определяет эмоции по ОТКЛОНЕНИЮ от базового тона, а не абсолютному.
 * - PitchVariance → arousal (Grok): высокая вариация = экспрессивная речь.
 * - SpectralFlux → arousal (Gemini): взрывные звуки усиливают возбуждение.
 * - Thoughtfulness (Gemini): растёт при паузах AI, падает при речи.
 * - RMS dynamics (Claude): отслеживает динамический диапазон.
 */
class ProsodyTracker {

    private var smoothPitch = 0f
    private var prevRms = 0f
    private var silenceMs = 0L

    // Baseline pitch адаптируется к голосу (Gemini)
    private var baselinePitch = 0f
    private var baselineInitialized = false

    // RMS dynamics (Claude)
    private val rmsHistory = FloatArray(10)
    private var rmsIdx = 0
    private var energyDynamicRange = 0f

    fun update(features: AudioFeatures, prosody: EmotionalProsody, dtMs: Long) {
        val dt = dtMs / 1000f

        // ═══ ТИШИНА: decay эмоций, рост thoughtfulness ═══
        if (!features.hasVoice && features.zcr < 0.1f) {
            silenceMs += dtMs

            prosody.arousal = max(0f, prosody.arousal - dt * 1.4f)
            prosody.valence *= (1f - dt * 0.7f)

            // AI "думает" (Gemini): thoughtfulness растёт после 500ms паузы
            if (silenceMs > 500) {
                prosody.thoughtfulness = min(1f, prosody.thoughtfulness + dt * 0.8f)
            }

            prevRms = 0f
            return
        }

        // Есть голос — сбрасываем тишину
        silenceMs = 0
        prosody.thoughtfulness = max(0f, prosody.thoughtfulness - dt * 5f)

        // ═══ RMS DYNAMICS (Claude) ═══
        rmsHistory[rmsIdx % rmsHistory.size] = features.rms
        rmsIdx++
        if (rmsIdx >= rmsHistory.size) {
            var minR = 1f; var maxR = 0f
            for (r in rmsHistory) {
                if (r > 0.01f) { minR = min(minR, r); maxR = max(maxR, r) }
            }
            energyDynamicRange = (maxR - minR).coerceIn(0f, 1f)
        }

        // ═══ AROUSAL (возбуждение) ═══
        // Три источника: RMS spike + spectralFlux (Gemini) + pitchVariance (Grok)
        val rmsSpike = max(0f, (features.rms - prevRms) * 6f)
        val fluxContrib = features.spectralFlux * 2f
        val pitchVarContrib = features.pitchVariance * 1.5f

        val arousalTarget = (
                rmsSpike * 0.3f +
                        fluxContrib * 0.25f +
                        pitchVarContrib * 0.25f +
                        energyDynamicRange * 0.15f +
                        features.rms * 0.05f
                ).coerceIn(0f, 1f)

        // Быстрый рост, медленный спад
        if (arousalTarget > prosody.arousal) {
            prosody.arousal += (arousalTarget - prosody.arousal) * dt * 10f
        } else {
            prosody.arousal += (arousalTarget - prosody.arousal) * dt * 2f
        }

        // ═══ VALENCE (эмоциональный окрас) через Pitch + Baseline (Gemini + Claude) ═══
        if (features.pitch > 0f) {
            if (smoothPitch == 0f) smoothPitch = features.pitch
            smoothPitch += (features.pitch - smoothPitch) * 10f * dt

            // Baseline adaptation (Gemini)
            if (!baselineInitialized) {
                baselinePitch = features.pitch
                baselineInitialized = true
            } else {
                baselinePitch += (features.pitch - baselinePitch) * 0.05f * dt
            }

            // Отклонение от базового тона (Gemini: намного точнее чем абсолютный pitch)
            val pitchDelta = smoothPitch - baselinePitch

            // Pitch variance усиливает эмоциональный сигнал (Grok)
            val pVar = features.pitchVariance

            val targetValence = when {
                // Радость/смех: высокий тон + вариация + высокие частоты
                pitchDelta > 25f && pVar > 0.3f && features.energyHigh > 0.15f -> 0.8f
                // Энтузиазм: высокий тон + arousal
                pitchDelta > 18f && prosody.arousal > 0.35f -> 0.5f
                // Экспрессивная позитивность: высокая вариация при повышенном тоне
                pitchDelta > 10f && pVar > 0.25f -> 0.35f
                // Дружелюбие: слегка повышен тон
                pitchDelta > 8f -> 0.2f
                // Серьёзность/раздражение: низкий тон + высокий arousal
                pitchDelta < -15f && prosody.arousal > 0.5f -> -0.55f
                // Грусть: низкий тон + мало энергии + монотонность
                pitchDelta < -10f && features.rms < 0.2f && pVar < 0.1f -> -0.35f
                // Нейтрально
                else -> 0f
            }

            prosody.valence += (targetValence - prosody.valence) * 2.5f * dt
        }

        prevRms = features.rms
        prosody.valence = prosody.valence.coerceIn(-1f, 1f)
        prosody.arousal = prosody.arousal.coerceIn(0f, 1f)
    }
}
