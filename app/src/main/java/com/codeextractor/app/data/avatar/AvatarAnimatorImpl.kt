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
 * AvatarAnimatorImpl v4 — финальный фикс "аватар замолкает посреди фразы".
 *
 * КОРНЕВАЯ ПРИЧИНА:
 * Gemini Live отправляет аудио БЫСТРЕЕ чем реальное время (сеть доставляет
 * 2 сек аудио за 100мс). Предыдущий фикс "обработать все чанки" решил проблему
 * DSP ring buffer, но создал новую: анимационный цикл "проглатывает" всё аудио
 * за 1 кадр, потом очередь пуста → holdoff истекает → аватар молчит.
 * А AudioTrack ещё 2 секунды играет звук!
 *
 * РЕШЕНИЕ:
 * remainingPlaybackMs — сколько мс аудио ещё НЕ проигралось через динамик.
 * feedAudio() увеличивает на длительность чанка. Каждый кадр уменьшает на dtMs.
 * Пока remainingPlaybackMs > 0 — аватар НЕ затухает (состояние COASTING).
 *
 * СОСТОЯНИЯ:
 * 1. ACTIVE:   свежие чанки + голос → полная обработка DSP
 * 2. QUIET:    свежие чанки без голоса → медленный decay
 * 3. COASTING: чанков нет, но remainingPlaybackMs > 0 → поддерживаем состояние
 * 4. FADEOUT:  holdoff после окончания воспроизведения
 * 5. SILENT:   полная тишина → idle
 */
@Singleton
class AvatarAnimatorImpl @Inject constructor() : AvatarAnimator {

    private val dsp = AudioDSPAnalyzer()
    private val prosodyTracker = ProsodyTracker()
    private val visemeMapper = VisemeMapper()
    private val coArticulator = CoArticulator()
    private val physics = FacePhysicsEngine()
    private val headMotion = HeadMotionEngine()
    private val idle = IdleAnimator()

    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()

    private val features = AudioFeatures()
    private val prosody = EmotionalProsody()

    // ═══ Smoothed features ═══
    private var smoothRms = 0f
    private var smoothLo = 0f
    private var smoothMid = 0f
    private var smoothHi = 0f

    // ═══ КЛЮЧЕВОЙ ФИКС: трекинг времени воспроизведения ═══
    @Volatile private var remainingPlaybackMs = 0L

    // ═══ Holdoff после окончания воспроизведения ═══
    private var audioHoldoffMs = 0L
    private var lastChunkHadVoice = false

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
        private const val AUDIO_HOLDOFF_MS = 250L
        private const val MAX_QUEUE_SIZE = 80
        private const val FRAME_DELAY_MS = 16L
        // 24000 Hz × 2 bytes (16-bit mono) = 48 bytes/ms
        private const val BYTES_PER_MS = 48f
        // Coasting: ~15% decay в секунду (очень медленно)
        private const val COAST_DECAY_PER_MS = 0.00015f
    }

    override fun feedAudio(pcmData: ByteArray) {
        if (audioQueue.size < MAX_QUEUE_SIZE) {
            audioQueue.add(pcmData)
        }
        // Трекинг: каждый чанк увеличивает оставшееся время воспроизведения
        remainingPlaybackMs += (pcmData.size / BYTES_PER_MS).toLong()
    }

    override fun setSpeaking(speaking: Boolean) {
        this.speaking = speaking
        if (!speaking) {
            // Позволяем доиграть максимум 400ms → плавный fade-out
            // Работает и для TurnComplete, и для Interrupted
            remainingPlaybackMs = minOf(remainingPlaybackMs, 400L)
        }
    }

    override fun start() {
        if (running) return
        running = true
        physics.reset(); headMotion.reset(); coArticulator.reset()
        smoothRms = 0f; smoothLo = 0f; smoothMid = 0f; smoothHi = 0f
        audioHoldoffMs = 0L; lastChunkHadVoice = false; remainingPlaybackMs = 0L

        animJob = scope.launch {
            var lastTime = System.currentTimeMillis()

            while (isActive && running) {
                val now = System.currentTimeMillis()
                val dtMs = (now - lastTime).coerceIn(1, 32)
                lastTime = now

                // ═══ 1. DRAIN: все чанки → DSP ring buffer ═══
                var processedChunks = 0
                while (audioQueue.isNotEmpty()) {
                    val chunk = audioQueue.poll() ?: break
                    dsp.analyze(chunk, features)
                    processedChunks++
                }
                val hasAudio = processedChunks > 0

                // ═══ 2. COUNTDOWN оставшегося воспроизведения ═══
                remainingPlaybackMs = (remainingPlaybackMs - dtMs).coerceAtLeast(0)
                val isPlaybackActive = remainingPlaybackMs > 0

                // ═══ 3. STATE MACHINE ═══
                when {
                    // ── ACTIVE: свежие чанки с голосом ──
                    hasAudio && features.hasVoice -> {
                        lastChunkHadVoice = true
                        audioHoldoffMs = AUDIO_HOLDOFF_MS

                        val upDown = if (features.rms > smoothRms) RMS_SMOOTH_UP else RMS_SMOOTH_DOWN
                        smoothRms = smoothRms * upDown + features.rms * (1f - upDown)
                        smoothLo = smoothLo * ENERGY_SMOOTH + features.energyLow * (1f - ENERGY_SMOOTH)
                        smoothMid = smoothMid * ENERGY_SMOOTH + features.energyMid * (1f - ENERGY_SMOOTH)
                        smoothHi = smoothHi * ENERGY_SMOOTH + features.energyHigh * (1f - ENERGY_SMOOTH)

                        features.rms = smoothRms
                        features.energyLow = smoothLo
                        features.energyMid = smoothMid
                        features.energyHigh = smoothHi
                    }

                    // ── QUIET: аудио есть, голоса нет ──
                    hasAudio && !features.hasVoice -> {
                        clearTransientFeatures()
                        if (isPlaybackActive || audioHoldoffMs > 0) {
                            smoothRms *= 0.94f
                            features.rms = smoothRms
                            features.hasVoice = smoothRms > 0.01f
                            audioHoldoffMs = (audioHoldoffMs - dtMs).coerceAtLeast(0)
                        } else {
                            decaySmoothed(dtMs)
                        }
                    }

                    // ── COASTING: чанков нет, но аудио ещё играет ──
                    isPlaybackActive -> {
                        clearTransientFeatures()
                        // НЕ затухаем! Аудио всё ещё звучит из динамика.
                        val coastDecay = 1f - dtMs * COAST_DECAY_PER_MS
                        smoothRms *= coastDecay
                        smoothLo *= coastDecay
                        smoothMid *= coastDecay
                        smoothHi *= coastDecay

                        features.rms = smoothRms
                        features.energyLow = smoothLo
                        features.energyMid = smoothMid
                        features.energyHigh = smoothHi
                        features.hasVoice = smoothRms > 0.005f

                        // Держим holdoff заряженным для плавного перехода после coasting
                        audioHoldoffMs = AUDIO_HOLDOFF_MS
                    }

                    // ── FADEOUT / SILENT ──
                    else -> {
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
                }

                // ═══ 4. PROSODY → VISEMES → PHYSICS → HEAD ═══
                prosodyTracker.update(features, prosody, dtMs)

                val rawVisemes = visemeMapper.map(features, prosody)
                val smoothVisemes = coArticulator.process(rawVisemes)

                val isAnimActive = speaking || isPlaybackActive || audioHoldoffMs > 0
                val idleWeights = idle.update(dtMs, isAnimActive)
                for (i in 0 until ARKit.COUNT) {
                    physics.setTarget(i, maxOf(smoothVisemes[i], idleWeights[i]))
                }

                val resolved = physics.update(dtMs)

                headMotion.update(
                    dtMs = dtMs,
                    rms = smoothRms,
                    arousal = prosody.arousal,
                    thoughtfulness = prosody.thoughtfulness,
                    isSpeaking = isAnimActive,
                    flux = features.spectralFlux
                )

                // ═══ 5. EMIT ═══
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
        smoothRms *= decay; smoothLo *= decay; smoothMid *= decay; smoothHi *= decay
        features.rms = smoothRms
        features.energyLow = smoothLo; features.energyMid = smoothMid; features.energyHigh = smoothHi
        features.hasVoice = false
    }

    private fun clearTransientFeatures() {
        features.spectralFlux = 0f
        features.isPlosive = false
    }

    override fun stop() {
        running = false
        animJob?.cancel()
        audioQueue.clear()
        physics.reset(); headMotion.reset(); coArticulator.reset()
        smoothRms = 0f; smoothLo = 0f; smoothMid = 0f; smoothHi = 0f
        remainingPlaybackMs = 0L
    }
}
