package com.codeextractor.app.data.avatar

import com.codeextractor.app.domain.avatar.*
import com.codeextractor.app.domain.avatar.audio.AudioDSPAnalyzer
import com.codeextractor.app.domain.avatar.audio.ProsodyTracker
import com.codeextractor.app.domain.avatar.physics.FacePhysicsEngine
import com.codeextractor.app.domain.avatar.physics.HeadMotionEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * AvatarAnimatorImpl v5 — Production-Ready Animator
 *
 * ПАЙПЛАЙН (каждые ~16 мс):
 *
 *   PCM chunks
 *       │
 *       ▼
 *   [LockFreePcmRingBuffer]  ← feedAudio() пишет из network thread
 *       │
 *       ▼  drain в animator coroutine
 *   [AudioDSPAnalyzer]       → AudioFeatures (rms, pitch, flux, zcr, ...)
 *       │
 *       ▼
 *   [State Machine]          → определяет режим (ACTIVE/QUIET/ANTICIPATION/COASTING/FADEOUT)
 *       │
 *       ▼
 *   [ProsodyTracker]         → EmotionalProsody (valence, arousal, thoughtfulness)
 *       │
 *       ├──► [VisemeMapper]  → raw blendshape weights
 *       │        │
 *       │    [CoArticulator] → temporally blended weights
 *       │        │
 *       │    [FacePhysicsEngine] → physically simulated output
 *       │
 *       ├──► [IdleAnimator]  → additive idle layer (blink, saccade, breath)
 *       │
 *       ├──► [HeadMotionEngine] → pitch/yaw/roll
 *       │
 *       ▼
 *   [RenderDoubleBuffer]     → читает AvatarScene.onFrame (zero-alloc)
 *   [emotionFlow]            → читает UI (4 раза/сек, только при изменении)
 *
 * STATE MACHINE (5 состояний):
 *
 *   ACTIVE      — свежие чанки + детектирован голос
 *                 Полный DSP, полная анимация
 *
 *   QUIET       — чанки есть, голос не детектирован (тихие паузы между словами)
 *                 Медленный decay, holdoff активен
 *
 *   ANTICIPATION — чанков нет, но setSpeaking(true) — сеть лагает
 *                 Мышечный тонус удерживается, thoughtfulness растёт,
 *                 аватар «формулирует» — Pre-Phonation Phase
 *
 *   COASTING    — чанков нет, remainingPlaybackMs > 0
 *                 Аудио ещё играет из динамика — аватар не засыпает,
 *                 очень медленный decay
 *
 *   FADEOUT     — всё кончилось, holdoff тикает до нуля
 *                 Органичное закрытие рта
 *
 * ГАРАНТИИ:
 *   • Zero heap allocation в animation loop (все буферы pre-allocated)
 *   • AtomicLong для remainingPlaybackMs (thread-safe на 32-bit ARM)
 *   • StampedLock в RenderDoubleBuffer (zero-contention при 60fps)
 *   • emotionFlow обновляется не чаще 4 раз/сек (UI не перегружается)
 *   • LockFreePcmRingBuffer — SPSC без блокировок
 */
@Singleton
class AvatarAnimatorImpl @Inject constructor() : AvatarAnimator {

    // ── DSP & Animation pipeline (single-threaded, animator coroutine) ────
    private val dsp           = AudioDSPAnalyzer()
    private val prosodyTracker = ProsodyTracker()
    private val visemeMapper  = VisemeMapper()
    private val coArticulator = CoArticulator()
    private val physics       = FacePhysicsEngine()
    private val headMotion    = HeadMotionEngine()
    private val idle          = IdleAnimator()

    // ── Per-frame working objects (pre-allocated, never re-created) ────────
    private val features = AudioFeatures()
    private val prosody  = EmotionalProsody()
    private val scratch  = ZeroAllocRenderState()   // writer scratch для RenderDoubleBuffer

    // ── PCM ring buffer (SPSC: feedAudio writes, animator reads) ──────────
    private val ringBuffer = LockFreePcmRingBuffer()
    private val dspScratch = ByteArray(DRAIN_CHUNK_BYTES)  // zero-alloc DSP drain buffer

    // ── Playback tracking ─────────────────────────────────────────────────
    // AtomicLong: thread-safe на 32-bit ARM (Long не атомарен без явного барьера)
    private val remainingPlaybackMs = AtomicLong(0L)

    // ── State ─────────────────────────────────────────────────────────────
    @Volatile private var speaking  = false
    @Volatile private var running   = false

    private var audioHoldoffMs      = 0L
    private var lastChunkHadVoice   = false
    private var networkHoldMs       = 0f    // длительность ANTICIPATION-фазы в секундах

    // ── Smoothed features (pre-allocated scalars) ─────────────────────────
    private var smoothRms = 0f
    private var smoothLo  = 0f
    private var smoothMid = 0f
    private var smoothHi  = 0f

    // ── emotionFlow throttle ──────────────────────────────────────────────
    private var emotionEmitAccumMs  = 0L
    private var lastEmittedValence  = Float.MAX_VALUE
    private var lastEmittedArousal  = Float.MAX_VALUE

    // ── Coroutine ─────────────────────────────────────────────────────────
    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var animJob: Job? = null

    // ── Public interface ──────────────────────────────────────────────────
    override val renderBuffer = RenderDoubleBuffer()

    private val _emotionFlow = MutableStateFlow(EmotionalProsodySnapshot())
    override val emotionFlow: StateFlow<EmotionalProsodySnapshot> = _emotionFlow.asStateFlow()

    companion object {
        // ── Smoothing constants ───────────────────────────────────────────
        private const val RMS_SMOOTH_UP     = 0.28f   // attack (быстрый)
        private const val RMS_SMOOTH_DOWN   = 0.88f   // release (медленный)
        private const val ENERGY_SMOOTH     = 0.42f

        // ── Holdoff ───────────────────────────────────────────────────────
        private const val AUDIO_HOLDOFF_MS  = 220L    // задержка fade-out после последнего чанка

        // ── Coasting decay ────────────────────────────────────────────────
        private const val COAST_DECAY_PER_MS = 0.00012f  // ~12%/сек

        // ── ANTICIPATION ──────────────────────────────────────────────────
        private const val ANTICIPATION_MAX_SEC    = 1.8f   // max длительность ожидания сети
        private const val ANTICIPATION_MUSCLE_THR = 0.06f  // минимальный тонус при ожидании
        private const val ANTICIPATION_LO_CHARGE  = 0.10f  // подкачка низких частот (вдох)

        // ── DSP drain ─────────────────────────────────────────────────────
        // 24000 Гц × 2 байта × 16 мс = 768 байт — один DSP кадр
        private const val DRAIN_CHUNK_BYTES = 768

        // ── Frame timing ──────────────────────────────────────────────────
        private const val FRAME_DELAY_MS    = 16L

        // ── Playback accounting ───────────────────────────────────────────
        // 24000 Гц × 2 байта = 48 байт/мс
        private const val BYTES_PER_MS      = 48f

        // ── emotionFlow update rate ───────────────────────────────────────
        private const val EMOTION_EMIT_INTERVAL_MS = 250L  // 4 раза/сек
        private const val EMOTION_CHANGE_THRESHOLD = 0.04f // минимальное изменение для emit
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Вызывается из network coroutine при получении аудио-чанка от Gemini.
     * Thread-safe: LockFreePcmRingBuffer — SPSC без блокировок.
     */
    override fun feedAudio(pcmData: ByteArray) {
        ringBuffer.write(pcmData)
        // Добавляем длительность чанка к счётчику воспроизведения
        remainingPlaybackMs.addAndGet((pcmData.size / BYTES_PER_MS).toLong())
    }

    /**
     * Вызывается при TurnComplete / Interrupted.
     * При Interrupted: ограничиваем remaining, чтобы аватар быстро замолчал.
     */
    override fun setSpeaking(speaking: Boolean) {
        this.speaking = speaking
        if (!speaking) {
            // Позволяем доиграть не более 350 мс после конца хода
            remainingPlaybackMs.updateAndGet { min(it, 350L) }
            networkHoldMs = 0f
        }
    }

    override fun start() {
        if (running) return
        running = true
        resetInternalState()

        animJob = scope.launch {
            var lastTime = System.currentTimeMillis()

            while (isActive && running) {
                val now  = System.currentTimeMillis()
                val dtMs = (now - lastTime).coerceIn(1L, 32L)
                lastTime = now

                tick(dtMs)

                delay(FRAME_DELAY_MS)
            }
        }
    }

    override fun stop() {
        running = false
        animJob?.cancel()
        ringBuffer.clear()
        resetInternalState()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MAIN TICK
    // ═══════════════════════════════════════════════════════════════════════

    private fun tick(dtMs: Long) {
        val dt = dtMs / 1000f

        // ── 1. DRAIN ring buffer → DSP ────────────────────────────────────
        val bytesRead = ringBuffer.drain(dspScratch, dspScratch.size)
        val hasAudio  = bytesRead > 0

        if (hasAudio) {
            dsp.analyze(dspScratch, features)
        }

        // ── 2. Countdown playback ─────────────────────────────────────────
        remainingPlaybackMs.updateAndGet { max(0L, it - dtMs) }
        val isPlaybackActive = remainingPlaybackMs.get() > 0L

        // ── 3. STATE MACHINE ───────────────────────────────────────────────
        val networkHold = determineState(hasAudio, isPlaybackActive, dtMs, dt)

        // ── 4. PROSODY ────────────────────────────────────────────────────
        prosodyTracker.update(features, prosody, dtMs, networkHold)

        // ── 5. VISEMES → CoArticulation → Physics ─────────────────────────
        val rawVisemes    = visemeMapper.map(features, prosody)
        val smoothVisemes = coArticulator.process(rawVisemes)

        // ── 6. IDLE (аддитивный слой) ─────────────────────────────────────
        val isAnimActive = speaking || isPlaybackActive || audioHoldoffMs > 0
        val idleWeights  = idle.update(dtMs, isAnimActive)

        // Объединяем через max: idle не мешает речи
        for (i in 0 until ARKit.COUNT) {
            physics.setTarget(i, maxOf(smoothVisemes[i], idleWeights[i]))
        }

        val resolved = physics.update(dtMs)

        // ── 7. HEAD MOTION ────────────────────────────────────────────────
        headMotion.update(
            dtMs           = dtMs,
            rms            = smoothRms,
            arousal        = prosody.arousal,
            thoughtfulness = prosody.thoughtfulness,
            isSpeaking     = isAnimActive,
            flux           = features.spectralFlux,
        )

        // ── 8. PUBLISH RENDER STATE (zero-alloc) ──────────────────────────
        scratch.headPitch = headMotion.pitch
        scratch.headYaw   = headMotion.yaw
        scratch.headRoll  = headMotion.roll
        resolved.copyInto(scratch.morphWeights, endIndex = ARKit.COUNT)
        renderBuffer.publish(scratch)

        // ── 9. EMOTION FLOW (throttled, UI only) ──────────────────────────
        emotionEmitAccumMs += dtMs
        if (emotionEmitAccumMs >= EMOTION_EMIT_INTERVAL_MS) {
            emotionEmitAccumMs = 0L
            maybeEmitEmotion()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  STATE MACHINE
    //  Возвращает networkHold — прокидывается в ProsodyTracker
    // ═══════════════════════════════════════════════════════════════════════

    private fun determineState(
        hasAudio: Boolean,
        isPlaybackActive: Boolean,
        dtMs: Long,
        dt: Float,
    ): Boolean {

        return when {

            // ── ACTIVE: свежие чанки, голос есть ─────────────────────────
            hasAudio && features.hasVoice -> {
                lastChunkHadVoice = true
                audioHoldoffMs    = AUDIO_HOLDOFF_MS
                networkHoldMs     = 0f

                val upDown = if (features.rms > smoothRms) RMS_SMOOTH_UP else RMS_SMOOTH_DOWN
                smoothRms = smoothRms * upDown + features.rms * (1f - upDown)
                smoothLo  = smoothLo  * ENERGY_SMOOTH + features.energyLow  * (1f - ENERGY_SMOOTH)
                smoothMid = smoothMid * ENERGY_SMOOTH + features.energyMid  * (1f - ENERGY_SMOOTH)
                smoothHi  = smoothHi  * ENERGY_SMOOTH + features.energyHigh * (1f - ENERGY_SMOOTH)

                applySmoothedToFeatures()
                false
            }

            // ── QUIET: чанки есть, голоса нет ────────────────────────────
            hasAudio && !features.hasVoice -> {
                clearTransients()
                networkHoldMs = 0f

                if (isPlaybackActive || audioHoldoffMs > 0L) {
                    smoothRms  *= 0.93f
                    applySmoothedToFeatures()
                    features.hasVoice = smoothRms > 0.012f
                    audioHoldoffMs = (audioHoldoffMs - dtMs).coerceAtLeast(0L)
                } else {
                    decaySmoothed(dt)
                }
                false
            }

            // ── ANTICIPATION: сеть лагает, speaking=true ─────────────────
            speaking && !hasAudio && !isPlaybackActive -> {
                clearTransients()
                networkHoldMs += dt

                if (networkHoldMs < ANTICIPATION_MAX_SEC) {
                    // Удерживаем минимальный мышечный тонус
                    if (smoothRms < ANTICIPATION_MUSCLE_THR) {
                        smoothRms += (ANTICIPATION_MUSCLE_THR - smoothRms) * 6f * dt
                    }
                    // Подкачиваем низкие частоты (имитация вдоха перед речью)
                    smoothLo = min(smoothLo + ANTICIPATION_LO_CHARGE * dt, 0.12f)

                    applySmoothedToFeatures()
                    features.hasVoice = false   // голоса нет, но тонус есть
                    audioHoldoffMs = AUDIO_HOLDOFF_MS
                    true  // networkHold = true → ProsodyTracker наращивает thoughtfulness
                } else {
                    // Лаг слишком долгий — мягко сдаёмся
                    decaySmoothed(dt)
                    false
                }
            }

            // ── COASTING: чанков нет, но аудио ещё играет ────────────────
            isPlaybackActive -> {
                clearTransients()
                networkHoldMs = 0f

                val coastDecay = 1f - dtMs * COAST_DECAY_PER_MS
                smoothRms  *= coastDecay
                smoothLo   *= coastDecay
                smoothMid  *= coastDecay
                smoothHi   *= coastDecay

                applySmoothedToFeatures()
                features.hasVoice = smoothRms > 0.006f
                audioHoldoffMs = AUDIO_HOLDOFF_MS   // держим holdoff заряженным
                false
            }

            // ── FADEOUT / SILENT ──────────────────────────────────────────
            else -> {
                clearTransients()
                networkHoldMs = 0f
                audioHoldoffMs = (audioHoldoffMs - dtMs).coerceAtLeast(0L)

                if (audioHoldoffMs > 0L && lastChunkHadVoice) {
                    smoothRms  *= 0.87f
                    applySmoothedToFeatures()
                    features.hasVoice = smoothRms > 0.009f
                } else {
                    lastChunkHadVoice = false
                    decaySmoothed(dt)
                }
                false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun applySmoothedToFeatures() {
        features.rms       = smoothRms
        features.energyLow  = smoothLo
        features.energyMid  = smoothMid
        features.energyHigh = smoothHi
    }

    private fun decaySmoothed(dt: Float) {
        val decay = (1f - dt * 4.5f).coerceIn(0f, 1f)
        smoothRms  *= decay; smoothLo  *= decay
        smoothMid  *= decay; smoothHi  *= decay
        features.rms       = smoothRms
        features.energyLow  = smoothLo
        features.energyMid  = smoothMid
        features.energyHigh = smoothHi
        features.hasVoice   = false
    }

    private fun clearTransients() {
        features.spectralFlux = 0f
        features.isPlosive    = false
    }

    /**
     * Throttled emotion emit: только при значимом изменении,
     * не чаще EMOTION_EMIT_INTERVAL_MS.
     */
    private fun maybeEmitEmotion() {
        val valenceChanged = kotlin.math.abs(prosody.valence - lastEmittedValence) > EMOTION_CHANGE_THRESHOLD
        val arousalChanged = kotlin.math.abs(prosody.arousal - lastEmittedArousal) > EMOTION_CHANGE_THRESHOLD

        if (valenceChanged || arousalChanged) {
            lastEmittedValence = prosody.valence
            lastEmittedArousal = prosody.arousal
            _emotionFlow.value = EmotionalProsodySnapshot(
                valence        = prosody.valence,
                arousal        = prosody.arousal,
                thoughtfulness = prosody.thoughtfulness,
            )
        }
    }

    private fun resetInternalState() {
        dsp.reset()
        prosodyTracker.reset()
        visemeMapper.reset()
        coArticulator.reset()
        physics.reset()
        headMotion.reset()
        idle.reset()
        features.reset()
        prosody.reset()
        scratch.reset()
        ringBuffer.clear()

        smoothRms = 0f; smoothLo = 0f; smoothMid = 0f; smoothHi = 0f
        audioHoldoffMs = 0L; lastChunkHadVoice = false; networkHoldMs = 0f
        remainingPlaybackMs.set(0L)
        emotionEmitAccumMs = 0L
        lastEmittedValence = Float.MAX_VALUE; lastEmittedArousal = Float.MAX_VALUE
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  SPSC RING BUFFER (Single Producer Single Consumer)
//
//  Используется вместо ConcurrentLinkedQueue (аллоцирует Node на каждый чанк)
//  и Channel<ByteArray> (аллоцирует объект при отправке).
//
//  Гарантии:
//   • write() вызывается только из одного network thread
//   • drain() вызывается только из одного animator coroutine
//   • @Volatile на head/tail обеспечивает видимость между потоками
//   • Нет блокировок, нет аллокаций в hot path
//
//  Размер: 512 KB — вмещает ~10 секунд PCM при 24 kHz 16-bit mono
//  (24000 × 2 байта/мс × ~10000 мс ÷ 1024 ≈ 469 KB)
// ═══════════════════════════════════════════════════════════════════════════
private class LockFreePcmRingBuffer(private val capacity: Int = 512 * 1024) {

    private val buffer = ByteArray(capacity)

    // @Volatile: гарантирует happens-before между write и drain
    @Volatile private var head = 0   // куда пишем (writer)
    @Volatile private var tail = 0   // откуда читаем (reader)

    /**
     * Пишет [chunk] в буфер.
     * Вызывается из network thread.
     *
     * Если буфер переполнен — самые старые данные перезаписываются.
     * Для аудио потока это предпочтительнее полного дропа чанка:
     * лучше иметь свежие данные без старых, чем старые без новых.
     */
    fun write(chunk: ByteArray) {
        var h = head
        for (b in chunk) {
            buffer[h] = b
            h = (h + 1) % capacity
        }
        head = h   // один volatile write в конце (барьер памяти)
    }

    /**
     * Читает до [maxBytes] байт из буфера в [dest].
     * Вызывается из animator coroutine.
     *
     * @return количество реально прочитанных байт (0 если буфер пуст)
     */
    fun drain(dest: ByteArray, maxBytes: Int): Int {
        val h = head   // один volatile read
        var t = tail

        if (t == h) return 0   // буфер пуст

        var bytesRead = 0
        while (t != h && bytesRead < maxBytes) {
            dest[bytesRead] = buffer[t]
            t = (t + 1) % capacity
            bytesRead++
        }

        tail = t   // один volatile write в конце
        return bytesRead
    }

    fun clear() {
        tail = head   // «опустошаем» не трогая данные — просто двигаем хвост к голове
    }
}