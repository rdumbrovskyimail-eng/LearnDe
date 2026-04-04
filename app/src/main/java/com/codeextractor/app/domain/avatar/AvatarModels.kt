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
    }
    override fun hashCode() = morphWeights.contentHashCode()
}

/** Continuous emotion vector from prosody analysis */
class EmotionalProsody {
    var valence: Float = 0f       // -1 (anger/sad) .. +1 (happy/laugh)
    var arousal: Float = 0f       // 0 (calm) .. 1 (excited/shouting)
    var thoughtfulness: Float = 0f // 0..1 (pause/thinking)
}

/** Audio features extracted by DSP */
class AudioFeatures {
    var rms: Float = 0f
    var energyLow: Float = 0f     // 150-800 Hz: A, O vowels
    var energyMid: Float = 0f     // 800-2500 Hz: E, I vowels
    var energyHigh: Float = 0f    // 2500-8000 Hz: S, F, Sh fricatives
    var pitch: Float = 0f         // F0 in Hz (0 = unvoiced)
    var hasVoice: Boolean = false
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