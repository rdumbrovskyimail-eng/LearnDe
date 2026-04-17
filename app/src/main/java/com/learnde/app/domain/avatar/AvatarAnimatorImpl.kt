// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/domain/avatar/AvatarAnimatorImpl.kt
//
// ФИКСЫ:
//   [1] pause()/resume() — при паузе тик-цикл ждёт (длинный delay),
//       а не обрабатывает кадры. renderBuffer при этом не пишется —
//       AvatarScene продолжит рендерить последний валидный кадр.
//   [2] sanitize workingState перед publish() — любые NaN/Infinity
//       из физики превращаются в 0 (защита лица от "развала").
// ═══════════════════════════════════════════════════════════
package com.learnde.app.domain.avatar

import com.learnde.app.domain.avatar.audio.AudioDSPAnalyzer
import com.learnde.app.domain.avatar.audio.ProsodyTracker
import com.learnde.app.domain.avatar.linguistics.PhoneticRibbon
import com.learnde.app.domain.avatar.linguistics.TextAudioPacer
import com.learnde.app.domain.avatar.physics.FacePhysicsEngine
import com.learnde.app.domain.avatar.physics.HeadMotionEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class AvatarAnimatorImpl @Inject constructor() : AvatarAnimator {

    override val renderBuffer = RenderDoubleBuffer()
    private val _emotionFlow = MutableStateFlow(EmotionalProsodySnapshot())
    override val emotionFlow: StateFlow<EmotionalProsodySnapshot> = _emotionFlow.asStateFlow()

    private val workingState = ZeroAllocRenderState()
    private val audioFeatures = AudioFeatures()
    private val prosody = EmotionalProsody()

    private val flowController = SpeechFlowController()
    private val audioAnalyzer = AudioDSPAnalyzer()
    private val prosodyTracker = ProsodyTracker()
    private val ribbon = PhoneticRibbon()
    private val pacer = TextAudioPacer(ribbon)
    private val visemeMapper = DualChannelVisemeMapper()
    private val idleAnimator = IdleAnimator()
    private val coArticulator = CoArticulator()
    private val physics = FacePhysicsEngine()
    private val headMotion = HeadMotionEngine()

    private var job: Job? = null
    @Volatile private var isSpeaking = false
    @Volatile private var networkHold = false
    @Volatile private var paused = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ═══════════════════════════════════════════════════════════════════════

    override fun start() {
        if (job?.isActive == true) return
        paused = false

        job = scope.launch {
            var lastMs = System.nanoTime() / 1_000_000L

            while (isActive) {
                // При паузе — ждём и не считаем. Этого достаточно, чтобы уронить CPU.
                if (paused) {
                    delay(100)
                    lastMs = System.nanoTime() / 1_000_000L
                    continue
                }

                val nowMs = System.nanoTime() / 1_000_000L
                var dtMs = nowMs - lastMs
                if (dtMs <= 0L) dtMs = 1L
                lastMs = nowMs

                // 1. SUPERVISOR
                flowController.setTextAvailable(ribbon.hasReadable)
                flowController.tick(dtMs, audioFeatures.rms, audioFeatures.hasVoice)

                // 2. PROSODY
                prosodyTracker.update(audioFeatures, prosody, dtMs, networkHold)

                // 3. TEXT-AUDIO PACER
                pacer.tick(dtMs, audioFeatures, flowController)

                // 4. DUAL-CHANNEL VISEME MAPPER
                val rawWeights = visemeMapper.map(
                    audioFeatures, pacer.linguisticState, prosody, flowController,
                )

                // 5. IDLE + MERGE
                val idleWeights = idleAnimator.update(dtMs, isSpeaking)
                for (i in 0 until ARKit.COUNT) {
                    rawWeights[i] = max(rawWeights[i], idleWeights[i])
                }

                // 6. CO-ARTICULATION → PHYSICS → HEAD
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

                // 7. PUBLISH с санитизацией (защита от NaN/Infinity)
                for (i in 0 until ARKit.COUNT) {
                    val v = physWeights[i]
                    workingState.morphWeights[i] = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
                }
                workingState.headPitch = headMotion.pitch.takeIf { it.isFinite() } ?: 0f
                workingState.headYaw   = headMotion.yaw.takeIf { it.isFinite() } ?: 0f
                workingState.headRoll  = headMotion.roll.takeIf { it.isFinite() } ?: 0f

                renderBuffer.publish(workingState)

                val va = prosody.valence.takeIf { it.isFinite() } ?: 0f
                val ar = prosody.arousal.takeIf { it.isFinite() } ?: 0f
                val th = prosody.thoughtfulness.takeIf { it.isFinite() } ?: 0f

                _emotionFlow.value = EmotionalProsodySnapshot(
                    valence = va,
                    arousal = ar,
                    thoughtfulness = th,
                )

                delay(14)
            }
        }
    }

    override fun stop() {
        paused = false
        job?.cancel()
        job = null
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  INPUT API
    // ═══════════════════════════════════════════════════════════════════════

    override fun feedAudio(pcmData: ByteArray) {
        audioAnalyzer.analyze(pcmData, audioFeatures)
        pacer.onAudioChunk(pcmData.size)
        flowController.onAudioChunk(pcmData.size)
    }

    override fun feedModelText(text: String) {
        ribbon.feedTextChunk(text)
        flowController.onTextChunk()
        networkHold = false
    }

    override fun setSpeaking(speaking: Boolean) {
        isSpeaking = speaking
        if (!speaking) {
            networkHold = true
            flowController.onTurnComplete()
        }
    }

    override fun bargeInClear() {
        flowController.onBargeIn()
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
