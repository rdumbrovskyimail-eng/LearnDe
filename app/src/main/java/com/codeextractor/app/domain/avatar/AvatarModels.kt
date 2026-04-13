package com.codeextractor.app.domain.avatar

import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.locks.StampedLock

// ═══════════════════════════════════════════════════════════════════════════
//  ZERO-ALLOCATION RENDER PIPELINE
//
//  Почему не простой double-buffer?
//  На JVM true lock-free double-buffer требует 3 буфера (write / pending / read),
//  иначе writer может затереть буфер, который в этот момент читает renderer.
//  StampedLock с оптимистичным чтением даёт ту же производительность
//  (contention в 99.9% кадров при 60fps отсутствует) с корректной семантикой
//  и без лишних аллокаций.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Mutable render snapshot: 51 ARKit morph weights + head rotation.
 * Аллоцируется ОДИН РАЗ при старте, живёт всё время работы приложения.
 */
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

/**
 * Thread-safe рендер-буфер на StampedLock.
 *
 * Write: animator coroutine (период ~16 мс)
 * Read:  Compose onFrame callback (период ~16 мс, другой поток)
 *
 * [scratch] — приватный буфер writer'а, никогда не читается renderer'ом.
 * Это исключает необходимость в третьем буфере.
 */
class RenderDoubleBuffer {
    private val state   = ZeroAllocRenderState()  // shared: читают снаружи
    private val scratch = ZeroAllocRenderState()  // private: пишет только animator
    private val lock    = StampedLock()

    /** Вызывается ТОЛЬКО animator coroutine (single writer). */
    fun publish(src: ZeroAllocRenderState) {
        scratch.copyFrom(src)                     // готовим локально — без lock
        val stamp = lock.writeLock()
        try   { state.copyFrom(scratch) }
        finally { lock.unlockWrite(stamp) }
    }

    /**
     * Вызывается renderer'ом (любой поток, любое число читателей).
     * Оптимистичное чтение: lock берётся только при редкой гонке.
     */
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

/**
 * Вектор эмоций, обновляемый ProsodyTracker.
 * Single writer (animator coroutine). Non-critical reads by UI через snapshot.
 *
 * [cognitivePressure] — внутренний spring-аккумулятор: нелинейно нарастает
 *   при паузах (сетевой лаг Gemini, обдумывание), высвобождается «Эврикой»
 *   при первом новом аудиочанке. Публично не экспонируется.
 */
class EmotionalProsody {
    var valence: Float           = 0f   // −1 (злость/грусть) .. +1 (радость/смех)
    var arousal: Float           = 0f   //  0 (спокойно)      ..  1 (возбуждённо)
    var thoughtfulness: Float    = 0f   //  0 ..  1  (когнитивная нагрузка / пауза)
    var cognitivePressure: Float = 0f   // internal spring state

    fun reset() {
        valence = 0f; arousal = 0f; thoughtfulness = 0f; cognitivePressure = 0f
    }
}

/**
 * Иммутабельный snapshot для StateFlow → UI.
 * Эмитируется не чаще ~4 раз/сек (только при значимом изменении).
 * НЕ используется для per-frame рендеринга — для этого есть [RenderDoubleBuffer].
 */
data class EmotionalProsodySnapshot(
    val valence: Float        = 0f,
    val arousal: Float        = 0f,
    val thoughtfulness: Float = 0f,
)

// ═══════════════════════════════════════════════════════════════════════════
//  AUDIO FEATURES  (single-writer DSP, single-reader animator — no lock needed)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Per-frame аудиопризнаки. Аллоцируются ОДИН РАЗ, переиспользуются каждый кадр.
 *
 * Источники:
 *   rms / energyBands / pitch / hasVoice  → оригинал
 *   zcr / spectralFlux / isPlosive        → Gemini
 *   pitchVariance                         → Grok
 */
class AudioFeatures {
    // ── Базовые ──────────────────────────────────────────────────────────
    var rms: Float          = 0f
    var energyLow: Float    = 0f   // 150–800 Hz:   гласные А, О
    var energyMid: Float    = 0f   // 800–2500 Hz:  гласные Е, И; носовые
    var energyHigh: Float   = 0f   // 2500–8000 Hz: шипящие С, Ф, Ш
    var pitch: Float        = 0f   // F0 в Гц  (0 = безголосый)
    var hasVoice: Boolean   = false

    // ── Временны́е характеристики ─────────────────────────────────────────
    var zcr: Float          = 0f   // Zero-Crossing Rate — детектор фрикативов
    var spectralFlux: Float = 0f   // Резкость атаки — детектор транзиентов
    var isPlosive: Boolean  = false // Производный триггер: П, Б, Т, К

    // ── Pitch dynamics ────────────────────────────────────────────────────
    var pitchVariance: Float = 0f  // Экспрессивность тона (0 = монотонно)

    fun reset() {
        rms = 0f; energyLow = 0f; energyMid = 0f; energyHigh = 0f
        pitch = 0f; hasVoice = false
        zcr = 0f; spectralFlux = 0f; isPlosive = false
        pitchVariance = 0f
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  PUBLIC INTERFACE
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Контракт аниматора аватара.
 *
 * [renderBuffer]  — zero-alloc буфер для 60 fps render loop.
 *                   AvatarScene читает его в onFrame через [RenderDoubleBuffer.read].
 *
 * [emotionFlow]   — низкочастотный StateFlow для UI (значки, цветовые подсветки).
 *                   Не подходит для per-frame рендеринга.
 */
interface AvatarAnimator {
    val renderBuffer: RenderDoubleBuffer
    val emotionFlow: StateFlow<EmotionalProsodySnapshot>

    fun feedAudio(pcmData: ByteArray)
    fun setSpeaking(speaking: Boolean)
    fun start()
    fun stop()
}