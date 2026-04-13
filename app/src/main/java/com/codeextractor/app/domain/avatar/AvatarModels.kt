package com.codeextractor.app.domain.avatar

import kotlinx.coroutines.flow.StateFlow

/** Full render state per frame */
data class AvatarRenderState(
    val morphWeights: FloatArray = FloatArray(ARKit.COUNT),
    val headPitch: Float = 0f,
    val headYaw: Float = 0f,
    val headRoll: Float = 0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AvatarRenderState) return false
        return morphWeights.contentEquals(other.morphWeights)
                && headPitch == other.headPitch
                && headYaw == other.headYaw
                && headRoll == other.headRoll
    }

    override fun hashCode(): Int {
        var result = morphWeights.contentHashCode()
        result = 31 * result + headPitch.hashCode()
        result = 31 * result + headYaw.hashCode()
        result = 31 * result + headRoll.hashCode()
        return result
    }
}

/** Continuous emotion vector from prosody analysis */
class EmotionalProsody {
    var valence: Float = 0f       // -1 (anger/sad) .. +1 (happy/laugh)
    var arousal: Float = 0f       // 0 (calm) .. 1 (excited/shouting)
    var thoughtfulness: Float = 0f // 0..1 (pause/thinking)
}

/**
 * Audio features extracted by DSP.
 * Объединяет лучшее из всех трёх реализаций:
 * - Базовые band energies (original)
 * - ZCR для детекции фрикативов (Gemini)
 * - Spectral Flux + isPlosive для взрывных (Gemini + Claude)
 * - Pitch Variance для эмоциональной экспрессивности (Grok)
 */
class AudioFeatures {
    // ═══ Базовые ═══
    var rms: Float = 0f
    var energyLow: Float = 0f      // 150-800 Hz: A, O vowels
    var energyMid: Float = 0f      // 800-2500 Hz: E, I vowels, nasals
    var energyHigh: Float = 0f     // 2500-8000 Hz: S, F, Sh fricatives
    var pitch: Float = 0f          // F0 in Hz (0 = unvoiced)
    var hasVoice: Boolean = false

    // ═══ Новые метрики (из Gemini) ═══
    var zcr: Float = 0f            // Zero-Crossing Rate: шипящие/фрикативы
    var spectralFlux: Float = 0f   // Резкость атаки звука (transients)
    var isPlosive: Boolean = false  // Триггер для взрывных P, B, T, K

    // ═══ Новые метрики (из Grok) ═══
    var pitchVariance: Float = 0f  // Вариация тона (экспрессивность)
}

/** Main animator interface */
interface AvatarAnimator {
    val renderState: StateFlow<AvatarRenderState>
    val emotion: StateFlow<EmotionalProsody>
    fun feedAudio(pcmData: ByteArray)
    fun setSpeaking(speaking: Boolean)
    fun start()
    fun stop()
}
