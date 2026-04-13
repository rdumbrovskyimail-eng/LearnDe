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
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AvatarAnimatorImpl v3 — финальная версия.
 *
 * Критические решения:
 *
 * 1. DRAIN: Обрабатываем ВСЕ чанки через DSP (Claude).
 *    Gemini предлагал rate-based sync buffer, но это создаёт
 *    проблемы с threading (synchronized в hot loop) и fragile ring buffer.
 *    Мой holdoff + smoothing достигает того же эффекта надёжнее.
 *
 * 2. HOLDOFF: После последнего голосового чанка рот закрывается
 *    плавно за 180ms, а не мгновенно (Claude).
 *
 * 3. RMS SMOOTHING: Быстрый attack / медленный release создаёт
 *    "конверт" громкости, совпадающий с восприятием (Claude).
 *
 * 4. setSpeaking(false) НЕ очищает очередь — даёт доиграть
 *    последние чанки (Claude). Grok делал clear() — это баг.
 *
 * 5. HeadMotionEngine получает spectralFlux + thoughtfulness
 *    для flux-driven nods и cognitive gaze shift (Gemini).
 */
@Singleton
class AvatarAnimatorImpl @Inject constructor() : AvatarAnimator {

    // Sub-engines
    private val dsp = AudioDSPAnalyzer()
    private val prosodyTracker = ProsodyTracker()
    private val visemeMapper = VisemeMapper()
    private val coArticulator = CoArticulator()
    private val physics = FacePhysicsEngine()
    private val headMotion = HeadMotionEngine()
    private val idle = IdleAnimator()

    // Thread-safe audio queue (lock-free)
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()

    // Reusable (zero-alloc in hot loop)
    private val features = AudioFeatures()
    private val prosody = EmotionalProsody()

    // ═══ Smoothed features (Claude) ═══
    private var smoothRms = 0f
    private var smoothLo = 0f
    private var smoothMid = 0f
    private var smoothHi = 0f

    // ═══ Holdoff (Claude) ═══
    private var audioHoldoffMs = 0L
    private var lastChunkHadVoice = false

    // Output flows
    private val _renderState = MutableStateFlow(AvatarRenderState())
    override val renderState: StateFlow<AvatarRenderState> = _renderState.asStateFlow()

    private val _emotion = MutableStateFlow(EmotionalProsody())
    override val emotion: StateFlow<EmotionalProsody> = _emotion.asStateFlow()

    @Volatile private var speaking = false
    @Volatile private var running = false
    private var animJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val RMS_SMOOTH_UP = 0.3f
        private const val RMS_SMOOTH_DOWN = 0.86f
        private const val ENERGY_SMOOTH = 0.45f
        private const val AUDIO_HOLDOFF_MS = 200L
        private const val MAX_QUEUE_SIZE = 80
        private const val FRAME_DELAY_MS = 16L
    }

    override fun feedAudio(pcmData: ByteArray) {
        if (audioQueue.size < MAX_QUEUE_SIZE) {
            audioQueue.add(pcmData)
        }
    }

    override fun setSpeaking(speaking: Boolean) {
        this.speaking = speaking
        // НЕ очищаем очередь (Claude): holdoff доиграет последние чанки
    }

    override fun start() {
        if (running) return
        running = true
        physics.reset(); headMotion.reset(); coArticulator.reset()
        smoothRms = 0f; smoothLo = 0f; smoothMid = 0f; smoothHi = 0f
        audioHoldoffMs = 0L; lastChunkHadVoice = false

        animJob = scope.launch {
            var lastTime = System.currentTimeMillis()

            while (isActive && running) {
                val now = System.currentTimeMillis()
                val dtMs = (now - lastTime).coerceIn(1, 32)
                lastTime = now

                // ══════════════════════════════════════════════
                //  1. DRAIN: Обработать ВСЕ чанки из очереди
                //  Каждый чанк проходит через DSP ring buffer.
                //  Раньше терялось 95% данных → рот замолкал.
                // ══════════════════════════════════════════════
                var processedChunks = 0
                while (audioQueue.isNotEmpty()) {
                    val chunk = audioQueue.poll() ?: break
                    dsp.analyze(chunk, features)
                    processedChunks++
                }

                val hasAudio = processedChunks > 0

                // ══════════════════════════════════════════════
                //  2. SMOOTHING + HOLDOFF
                // ══════════════════════════════════════════════
                if (hasAudio && features.hasVoice) {
                    lastChunkHadVoice = true
                    audioHoldoffMs = AUDIO_HOLDOFF_MS

                    // Сглаживание: быстрый attack, медленный release
                    val upDown = if (features.rms > smoothRms) RMS_SMOOTH_UP else RMS_SMOOTH_DOWN
                    smoothRms = smoothRms * upDown + features.rms * (1f - upDown)
                    smoothLo = smoothLo * ENERGY_SMOOTH + features.energyLow * (1f - ENERGY_SMOOTH)
                    smoothMid = smoothMid * ENERGY_SMOOTH + features.energyMid * (1f - ENERGY_SMOOTH)
                    smoothHi = smoothHi * ENERGY_SMOOTH + features.energyHigh * (1f - ENERGY_SMOOTH)

                    // Подменяем features сглаженными значениями
                    features.rms = smoothRms
                    features.energyLow = smoothLo
                    features.energyMid = smoothMid
                    features.energyHigh = smoothHi

                } else if (hasAudio && !features.hasVoice) {
                    // Аудио есть, голоса нет — тихий фрагмент
                    // Обнуляем транзиенты чтобы VisemeMapper не триггерил на stale данных
                    clearTransientFeatures()
                    audioHoldoffMs = (audioHoldoffMs - dtMs).coerceAtLeast(0)
                    if (audioHoldoffMs > 0) {
                        smoothRms *= 0.92f
                        features.rms = smoothRms
                        features.hasVoice = smoothRms > 0.01f
                    } else {
                        decaySmoothed(dtMs)
                    }
                } else {
                    // Нет аудио — обнуляем все транзиенты
                    clearTransientFeatures()
                    audioHoldoffMs = (audioHoldoffMs - dtMs).coerceAtLeast(0)
                    if (audioHoldoffMs > 0 && lastChunkHadVoice) {
                        smoothRms *= 0.88f
                        features.rms = smoothRms
                        features.hasVoice = smoothRms > 0.008f
                    } else {
                        lastChunkHadVoice = false
                        decaySmoothed(dtMs)
                    }
                }

                // ══════════════════════════════════════════════
                //  3. PROSODY (всегда, даже в тишине)
                // ══════════════════════════════════════════════
                prosodyTracker.update(features, prosody, dtMs)

                // ══════════════════════════════════════════════
                //  4. VISEME → CO-ARTICULATION → PHYSICS
                // ══════════════════════════════════════════════
                val rawVisemes = visemeMapper.map(features, prosody)
                val smoothVisemes = coArticulator.process(rawVisemes)

                val idleWeights = idle.update(dtMs, speaking || audioHoldoffMs > 0)
                for (i in 0 until ARKit.COUNT) {
                    physics.setTarget(i, maxOf(smoothVisemes[i], idleWeights[i]))
                }

                val resolved = physics.update(dtMs)

                // ══════════════════════════════════════════════
                //  5. HEAD MOTION (Gemini: flux + thoughtfulness)
                // ══════════════════════════════════════════════
                headMotion.update(
                    dtMs = dtMs,
                    rms = smoothRms,
                    arousal = prosody.arousal,
                    thoughtfulness = prosody.thoughtfulness,  // Gemini: cognitive gaze
                    isSpeaking = speaking || audioHoldoffMs > 0,
                    flux = features.spectralFlux               // Gemini: flux-driven nods
                )

                // ══════════════════════════════════════════════
                //  6. EMIT RENDER STATE
                // ══════════════════════════════════════════════
                _renderState.value = AvatarRenderState(
                    morphWeights = resolved.copyOf(),
                    headPitch = headMotion.pitch,
                    headYaw = headMotion.yaw,
                    headRoll = headMotion.roll
                )
                _emotion.value = EmotionalProsody().also {
                    it.valence = prosody.valence
                    it.arousal = prosody.arousal
                    it.thoughtfulness = prosody.thoughtfulness
                }

                delay(FRAME_DELAY_MS)
            }
        }
    }

    private fun decaySmoothed(dtMs: Long) {
        val decay = (1f - (dtMs / 1000f) * 4f).coerceIn(0f, 1f)
        smoothRms *= decay
        smoothLo *= decay
        smoothMid *= decay
        smoothHi *= decay
        features.rms = smoothRms
        features.energyLow = smoothLo
        features.energyMid = smoothMid
        features.energyHigh = smoothHi
        features.hasVoice = false
    }

    /** Обнуляем транзиентные метрики, чтобы VisemeMapper не триггерил на stale данных */
    private fun clearTransientFeatures() {
        features.spectralFlux = 0f
        features.isPlosive = false
        // zcr оставляем — он не вызывает ложных триггеров и корректно декаится
    }

    override fun stop() {
        running = false
        animJob?.cancel()
        audioQueue.clear()
        physics.reset(); headMotion.reset(); coArticulator.reset()
        smoothRms = 0f; smoothLo = 0f; smoothMid = 0f; smoothHi = 0f
    }
}
