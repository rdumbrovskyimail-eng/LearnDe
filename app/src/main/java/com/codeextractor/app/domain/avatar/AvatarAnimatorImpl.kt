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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * AvatarAnimatorImpl v3 — SpeechFlowController-integrated.
 *
 * Pipeline (60fps):
 *   1. SpeechFlowController.tick()     ← ПЕРВЫЙ: определяет momentum
 *   2. ProsodyTracker.update()         ← эмоции
 *   3. TextAudioPacer.tick()           ← синхронизация текста (читает flow)
 *   4. DualChannelVisemeMapper.map()   ← артикуляция (читает flow)
 *   5. IdleAnimator + merge
 *   6. CoArticulator → Physics → Head → Publish
 */
@Singleton
class AvatarAnimatorImpl @Inject constructor() : AvatarAnimator {

    override val renderBuffer = RenderDoubleBuffer()
    private val _emotionFlow = MutableStateFlow(EmotionalProsodySnapshot())
    override val emotionFlow: StateFlow<EmotionalProsodySnapshot> = _emotionFlow.asStateFlow()

    private val workingState = ZeroAllocRenderState()
    private val audioFeatures = AudioFeatures()
    private val prosody = EmotionalProsody()

    // ── SUPERVISOR ────────────────────────────────────────────────────────
    private val flowController = SpeechFlowController()

    // ── DSP ───────────────────────────────────────────────────────────────
    private val audioAnalyzer = AudioDSPAnalyzer()
    private val prosodyTracker = ProsodyTracker()

    // ── Linguistics ───────────────────────────────────────────────────────
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

    override fun start() {
        if (job?.isActive == true) return

        job = scope.launch {
            var lastMs = System.nanoTime() / 1_000_000L

            while (isActive) {
                val nowMs = System.nanoTime() / 1_000_000L
                var dtMs = nowMs - lastMs
                if (dtMs <= 0L) dtMs = 1L
                lastMs = nowMs

                // ── 1. SUPERVISOR (определяет momentum и pauseDepth) ──────
                flowController.setTextAvailable(ribbon.hasReadable)
                flowController.tick(dtMs, audioFeatures.rms, audioFeatures.hasVoice)

                // ── 2. PROSODY (эмоции) ───────────────────────────────────
                prosodyTracker.update(audioFeatures, prosody, dtMs, networkHold)

                // ── 3. TEXT-AUDIO PACER (читает flowController) ────────────
                pacer.tick(dtMs, audioFeatures, flowController)

                // ── 4. DUAL-CHANNEL VISEME MAPPER (читает flowController) ─
                val rawWeights = visemeMapper.map(
                    audioFeatures, pacer.linguisticState, prosody, flowController,
                )

                // ── 5. IDLE + MERGE ───────────────────────────────────────
                val idleWeights = idleAnimator.update(dtMs, isSpeaking)
                for (i in 0 until ARKit.COUNT) {
                    rawWeights[i] = max(rawWeights[i], idleWeights[i])
                }

                // ── 6. CO-ARTICULATION → PHYSICS → HEAD ───────────────────
                val coArticulated = coArticulator.process(rawWeights)
                physics.setTargets(coArticulated)
                val physWeights = physics.update(dtMs)

                headMotion.update(
                    dtMs = dtMs, rms = audioFeatures.rms,
                    arousal = prosody.arousal,
                    thoughtfulness = prosody.thoughtfulness,
                    isSpeaking = isSpeaking || flowController.isInSpeechFlow,
                    flux = audioFeatures.spectralFlux,
                )

                // ── 7. PUBLISH ────────────────────────────────────────────
                for (i in 0 until ARKit.COUNT) {
                    workingState.morphWeights[i] = physWeights[i]
                }
                workingState.headPitch = headMotion.pitch
                workingState.headYaw = headMotion.yaw
                workingState.headRoll = headMotion.roll

                renderBuffer.publish(workingState)

                _emotionFlow.value = EmotionalProsodySnapshot(
                    valence = prosody.valence,
                    arousal = prosody.arousal,
                    thoughtfulness = prosody.thoughtfulness,
                )

                delay(14)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT API
    // ═══════════════════════════════════════════════════════════════════════

    override fun feedAudio(pcmData: ByteArray) {
        audioAnalyzer.analyze(pcmData, audioFeatures)
        pacer.onAudioChunk(pcmData.size)
        flowController.onAudioChunk(pcmData.size)  // ← supervisor знает
    }

    override fun feedModelText(text: String) {
        ribbon.feedTextChunk(text)
        flowController.onTextChunk()  // ← supervisor знает
        networkHold = false
    }

    override fun setSpeaking(speaking: Boolean) {
        isSpeaking = speaking
        if (!speaking) {
            networkHold = true
            flowController.onTurnComplete()  // ← supervisor начинает decay
        }
    }

    override fun bargeInClear() {
        flowController.onBargeIn()  // ← supervisor: мгновенный decay
        ribbon.flush()
        pacer.resetClocks()
        audioAnalyzer.reset()
        prosodyTracker.reset()
        visemeMapper.reset()
        coArticulator.reset()
        isSpeaking = false
        networkHold = false
    }
}
