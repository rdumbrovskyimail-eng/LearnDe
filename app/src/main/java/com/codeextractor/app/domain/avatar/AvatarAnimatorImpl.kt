package com.codeextractor.app.domain.avatar

import com.codeextractor.app.domain.avatar.audio.AudioDSPAnalyzer
import com.codeextractor.app.domain.avatar.audio.ProsodyTracker
import com.codeextractor.app.domain.avatar.linguistics.PhoneticRibbon
import com.codeextractor.app.domain.avatar.linguistics.TextAudioPacer
import com.codeextractor.app.domain.avatar.physics.FacePhysicsEngine
import com.codeextractor.app.domain.avatar.physics.HeadMotionEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * AvatarAnimatorImpl — Центральный контроллер анимации аватара.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  DUAL-CHANNEL PIPELINE (60 fps loop)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Каждый кадр (~16мс):
 *
 *   1. ProsodyTracker.update()     — эмоции из аудио
 *   2. TextAudioPacer.tick()       — синхронизация текста с аудио
 *                                    → обновляет LinguisticState
 *   3. DualChannelVisemeMapper.map() — GATE + TARGET + MODULATE
 *                                    → raw blendshape weights
 *   4. IdleAnimator.update()       — blink, saccade, breathing
 *   5. max(speech, idle)           — idle никогда не мешает речи
 *   6. CoArticulator.process()     — temporal phoneme blending
 *   7. FacePhysicsEngine.update()  — biomechanical soft-tissue sim
 *   8. HeadMotionEngine.update()   — head saccades, nods, sway
 *   9. RenderDoubleBuffer.publish() — lock-free output to renderer
 *
 * ВХОДЫ:
 *   feedAudio(pcm)     — от GeminiEvent.AudioChunk (IO thread)
 *   feedModelText(text) — от GeminiEvent.ModelText / OutputTranscript (IO thread)
 *   setSpeaking(bool)   — от TurnComplete / AudioChunk events
 *   bargeInClear()      — от GeminiEvent.Interrupted
 *
 * ZERO-ALLOCATION в горячем цикле:
 *   Все буферы pre-allocated. Единственный GC pressure — feedModelText
 *   (String → PhonemeToken list), но это на IO thread, не на render.
 */
class AvatarAnimatorImpl : AvatarAnimator {

    // ── Public outputs ────────────────────────────────────────────────────
    override val renderBuffer = RenderDoubleBuffer()
    private val _emotionFlow = MutableStateFlow(EmotionalProsodySnapshot())
    override val emotionFlow: StateFlow<EmotionalProsodySnapshot> = _emotionFlow.asStateFlow()

    // ── Working state (pre-allocated, zero-alloc) ─────────────────────────
    private val workingState = ZeroAllocRenderState()
    private val audioFeatures = AudioFeatures()
    private val prosody = EmotionalProsody()

    // ── DSP & Analysis ────────────────────────────────────────────────────
    private val audioAnalyzer = AudioDSPAnalyzer()
    private val prosodyTracker = ProsodyTracker()

    // ── Linguistics (TEXT CHANNEL) ────────────────────────────────────────
    private val ribbon = PhoneticRibbon()
    private val pacer = TextAudioPacer(ribbon)

    // ── Viseme & Animation ────────────────────────────────────────────────
    private val visemeMapper = DualChannelVisemeMapper()
    private val idleAnimator = IdleAnimator()
    private val coArticulator = CoArticulator()

    // ── Physics ───────────────────────────────────────────────────────────
    private val physics = FacePhysicsEngine()
    private val headMotion = HeadMotionEngine()

    // ── Lifecycle ─────────────────────────────────────────────────────────
    private var job: Job? = null
    @Volatile private var isSpeaking = false
    @Volatile private var networkHold = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ═══════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    override fun start() {
        if (job?.isActive == true) return

        job = scope.launch {
            var lastMs = System.nanoTime() / 1_000_000L

            while (isActive) {
                val nowMs = System.nanoTime() / 1_000_000L
                var dtMs = nowMs - lastMs
                if (dtMs <= 0L) dtMs = 1L
                lastMs = nowMs

                // ── 1. PROSODY (эмоции из аудио) ──────────────────────────
                prosodyTracker.update(audioFeatures, prosody, dtMs, networkHold)

                // ── 2. TEXT-AUDIO PACER (синхронизация ленты) ──────────────
                pacer.tick(dtMs, audioFeatures)

                // ── 3. DUAL-CHANNEL VISEME MAPPER (ядро!) ─────────────────
                val rawWeights = visemeMapper.map(
                    audioFeatures,
                    pacer.linguisticState,
                    prosody,
                )

                // ── 4. IDLE ANIMATOR (blink, saccade, breathing) ──────────
                val idleWeights = idleAnimator.update(dtMs, isSpeaking)

                // ── 5. MERGE: max(speech, idle) ───────────────────────────
                for (i in 0 until ARKit.COUNT) {
                    rawWeights[i] = max(rawWeights[i], idleWeights[i])
                }

                // ── 6. CO-ARTICULATION (temporal phoneme blending) ─────────
                val coArticulated = coArticulator.process(rawWeights)

                // ── 7. FACE PHYSICS (soft-tissue spring simulation) ───────
                physics.setTargets(coArticulated)
                val physWeights = physics.update(dtMs)

                // ── 8. HEAD MOTION (saccades, nods, sway) ─────────────────
                headMotion.update(
                    dtMs = dtMs,
                    rms = audioFeatures.rms,
                    arousal = prosody.arousal,
                    thoughtfulness = prosody.thoughtfulness,
                    isSpeaking = isSpeaking,
                    flux = audioFeatures.spectralFlux,
                )

                // ── 9. ASSEMBLE & PUBLISH ─────────────────────────────────
                for (i in 0 until ARKit.COUNT) {
                    workingState.morphWeights[i] = physWeights[i]
                }
                workingState.headPitch = headMotion.pitch
                workingState.headYaw = headMotion.yaw
                workingState.headRoll = headMotion.roll

                renderBuffer.publish(workingState)

                // ── 10. EMIT EMOTION SNAPSHOT (low-frequency) ─────────────
                _emotionFlow.value = EmotionalProsodySnapshot(
                    valence = prosody.valence,
                    arousal = prosody.arousal,
                    thoughtfulness = prosody.thoughtfulness,
                )

                // ── Target ~60 FPS ────────────────────────────────────────
                delay(14)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT API (called from IO threads)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * PCM аудио от Gemini → DSP анализ.
     * Также обновляет audio clock в Pacer для drift correction.
     */
    override fun feedAudio(pcmData: ByteArray) {
        audioAnalyzer.analyze(pcmData, audioFeatures)
        pacer.onAudioChunk(pcmData.size)
    }

    /**
     * Текст от Gemini → фонемный анализ → кольцевой буфер.
     * Pacer начнёт потреблять фонемы когда аудио подтвердит голос.
     */
    override fun feedModelText(text: String) {
        ribbon.feedTextChunk(text)
        networkHold = false
    }

    override fun setSpeaking(speaking: Boolean) {
        isSpeaking = speaking
        if (!speaking) {
            networkHold = true
        }
    }

    /**
     * Barge-in: пользователь перебил модель.
     * Сбрасываем ВСЕ: ленту, таймеры, визем-состояние.
     * Physics НЕ сбрасываем — пусть плавно закроет рот.
     */
    override fun bargeInClear() {
        ribbon.flush()
        pacer.onTurnBoundary()
        audioAnalyzer.reset()
        prosodyTracker.reset()
        visemeMapper.reset()
        coArticulator.reset()
        // physics: НЕ сбрасываем — плавный возврат к нейтрали
        // headMotion: НЕ сбрасываем — голова плавно вернётся
        isSpeaking = false
        networkHold = false
    }
}
