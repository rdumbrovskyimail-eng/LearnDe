package com.learnde.app.domain.avatar

import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.locks.StampedLock

// ═══════════════════════════════════════════════════════════════════════════
//  ZERO-ALLOCATION RENDER PIPELINE
// ═══════════════════════════════════════════════════════════════════════════

class ZeroAllocRenderState {
    val morphWeights: FloatArray = FloatArray(ARKit.COUNT)
    var headPitch: Float = 0f
    var headYaw: Float   = 0f
    var headRoll: Float  = 0f

    fun copyFrom(src: ZeroAllocRenderState) {
        System.arraycopy(src.morphWeights, 0, morphWeights, 0, ARKit.COUNT)
        headPitch = src.headPitch
        headYaw   = src.headYaw
        headRoll  = src.headRoll
    }

    fun reset() {
        morphWeights.fill(0f)
        headPitch = 0f; headYaw = 0f; headRoll = 0f
    }
}

class RenderDoubleBuffer {
    private val state   = ZeroAllocRenderState()
    private val scratch = ZeroAllocRenderState()
    private val lock    = StampedLock()

    fun publish(src: ZeroAllocRenderState) {
        scratch.copyFrom(src)
        val stamp = lock.writeLock()
        try   { state.copyFrom(scratch) }
        finally { lock.unlockWrite(stamp) }
    }

    fun read(dest: ZeroAllocRenderState) {
        var stamp = lock.tryOptimisticRead()
        dest.copyFrom(state)
        if (!lock.validate(stamp)) {
            stamp = lock.readLock()
            try   { dest.copyFrom(state) }
            finally { lock.unlockRead(stamp) }
        }
    }

    fun reset() { state.reset(); scratch.reset() }
}

// ═══════════════════════════════════════════════════════════════════════════
//  EMOTION / PROSODY
// ═══════════════════════════════════════════════════════════════════════════

class EmotionalProsody {
    var valence: Float           = 0f
    var arousal: Float           = 0f
    var thoughtfulness: Float    = 0f
    var cognitivePressure: Float = 0f

    fun reset() {
        valence = 0f; arousal = 0f; thoughtfulness = 0f; cognitivePressure = 0f
    }
}

data class EmotionalProsodySnapshot(
    val valence: Float        = 0f,
    val arousal: Float        = 0f,
    val thoughtfulness: Float = 0f,
)

// ═══════════════════════════════════════════════════════════════════════════
//  AUDIO FEATURES (обновлённый — с лингвистическими полями)
// ═══════════════════════════════════════════════════════════════════════════

class AudioFeatures {
    // ── Базовые (из DSP) ─────────────────────────────────────────────────
    var rms: Float          = 0f
    var energyLow: Float    = 0f
    var energyMid: Float    = 0f
    var energyHigh: Float   = 0f
    var pitch: Float        = 0f
    var hasVoice: Boolean   = false

    // ── Временны́е характеристики ─────────────────────────────────────────
    var zcr: Float          = 0f
    var spectralFlux: Float = 0f
    var isPlosive: Boolean  = false

    // ── Pitch dynamics ────────────────────────────────────────────────────
    var pitchVariance: Float = 0f

    fun reset() {
        rms = 0f; energyLow = 0f; energyMid = 0f; energyHigh = 0f
        pitch = 0f; hasVoice = false
        zcr = 0f; spectralFlux = 0f; isPlosive = false
        pitchVariance = 0f
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  PUBLIC INTERFACE (обновлённый — с текстовым каналом)
// ═══════════════════════════════════════════════════════════════════════════

interface AvatarAnimator {
    val renderBuffer: RenderDoubleBuffer
    val emotionFlow: StateFlow<EmotionalProsodySnapshot>

    /** PCM аудио от Gemini для DSP-анализа */
    fun feedAudio(pcmData: ByteArray)

    /** Текст от Gemini (ModelText / OutputTranscript) для фонемного анализа */
    fun feedModelText(text: String)

    /** Флаг: AI сейчас говорит */
    fun setSpeaking(speaking: Boolean)

    /** Barge-in: пользователь перебил — сбросить всё */
    fun bargeInClear()

    fun start()
    fun stop()
}
