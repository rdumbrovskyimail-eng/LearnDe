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
 * AvatarAnimatorImpl — Центральный контроллер анимации аватара.
 * Dual-Channel Pipeline: Text (phoneme-gated) + Audio (modulated) @ 60fps.
 */
@Singleton
class AvatarAnimatorImpl @Inject constructor() : AvatarAnimator {

    override val renderBuffer = RenderDoubleBuffer()
    private val _emotionFlow = MutableStateFlow(EmotionalProsodySnapshot())
    override val emotionFlow: StateFlow<EmotionalProsodySnapshot> = _emotionFlow.asStateFlow()

    private val workingState = ZeroAllocRenderState()
    private val audioFeatures = AudioFeatures()
    private val prosody = EmotionalProsody()

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

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun start() {
        if (job?.isActive == true) return

        job = scope.launch {
            var lastMs = System.nanoTime() / 1_000_000L

            while (isActive) {
                val nowMs = System.nanoTime() / 1_000_000L
                var dtMs = nowMs - lastMs
                if (dtMs <= 0L) dtMs = 1L
                lastMs = nowMs

                prosodyTracker.update(audioFeatures, prosody, dtMs, networkHold)
                pacer.tick(dtMs, audioFeatures)

                val rawWeights = visemeMapper.map(
                    audioFeatures, pacer.linguisticState, prosody,
                )

                val idleWeights = idleAnimator.update(dtMs, isSpeaking)
                for (i in 0 until ARKit.COUNT) {
                    rawWeights[i] = max(rawWeights[i], idleWeights[i])
                }

                val coArticulated = coArticulator.process(rawWeights)
                physics.setTargets(coArticulated)
                val physWeights = physics.update(dtMs)

                headMotion.update(
                    dtMs = dtMs, rms = audioFeatures.rms,
                    arousal = prosody.arousal,
                    thoughtfulness = prosody.thoughtfulness,
                    isSpeaking = isSpeaking,
                    flux = audioFeatures.spectralFlux,
                )

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

    override fun feedAudio(pcmData: ByteArray) {
        audioAnalyzer.analyze(pcmData, audioFeatures)
        pacer.onAudioChunk(pcmData.size)
    }

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

    override fun bargeInClear() {
        ribbon.flush()
        pacer.onTurnBoundary()
        audioAnalyzer.reset()
        prosodyTracker.reset()
        visemeMapper.reset()
        coArticulator.reset()
        isSpeaking = false
        networkHold = false
    }
}
