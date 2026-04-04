package com.codeextractor.app.data.avatar

import com.codeextractor.app.domain.avatar.*
import com.codeextractor.app.domain.avatar.audio.AudioDSPAnalyzer
import com.codeextractor.app.domain.avatar.audio.ProsodyTracker
import com.codeextractor.app.domain.avatar.physics.FacePhysicsEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvatarAnimatorImpl @Inject constructor() : AvatarAnimator {

    // Sub-engines
    private val dsp = AudioDSPAnalyzer()
    private val prosodyTracker = ProsodyTracker()
    private val visemeMapper = VisemeMapper()
    private val physics = FacePhysicsEngine()
    private val idle = IdleAnimator()

    // Thread-safe audio queue (lock-free)
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()

    // Reusable feature holders (zero alloc in hot loop)
    private val features = AudioFeatures()
    private val prosody = EmotionalProsody()

    // Output flows
    private val _renderState = MutableStateFlow(AvatarRenderState())
    override val renderState: StateFlow<AvatarRenderState> = _renderState.asStateFlow()

    private val _emotion = MutableStateFlow(EmotionalProsody())
    override val emotion: StateFlow<EmotionalProsody> = _emotion.asStateFlow()

    @Volatile private var speaking = false
    @Volatile private var running = false
    private var animJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun feedAudio(pcmData: ByteArray) {
        // Non-blocking, lock-free enqueue. Drop if queue too large.
        if (audioQueue.size < 32) {
            audioQueue.add(pcmData)
        }
    }

    override fun setSpeaking(speaking: Boolean) {
        this.speaking = speaking
        if (!speaking) {
            audioQueue.clear()
            // Let physics decay naturally (no snap)
        }
    }

    override fun start() {
        if (running) return
        running = true
        physics.reset()

        animJob = scope.launch {
            var lastTime = System.currentTimeMillis()

            while (isActive && running) {
                val now = System.currentTimeMillis()
                val dtMs = (now - lastTime).coerceIn(1, 32)
                lastTime = now

                // 1. Process latest audio chunk (drain queue, use last)
                var chunk: ByteArray? = null
                while (audioQueue.isNotEmpty()) {
                    chunk = audioQueue.poll()
                }

                if (chunk != null) {
                    // 2. DSP: extract spectral features
                    dsp.analyze(chunk, features)

                    // 3. Prosody: extract emotion from pitch/energy dynamics
                    prosodyTracker.update(features, prosody, dtMs)

                    // 4. Viseme mapping: features + emotion → target morph weights
                    val visemeTargets = visemeMapper.map(features, prosody)

                    // 5. Merge with idle animation (max blend)
                    val idleWeights = idle.update(dtMs, speaking)
                    for (i in 0 until ARKit.COUNT) {
                        physics.setTarget(i, maxOf(visemeTargets[i], idleWeights[i]))
                    }
                } else {
                    // No audio — idle only
                    if (!speaking) {
                        features.hasVoice = false
                        features.rms = 0f
                        prosodyTracker.update(features, prosody, dtMs)
                    }
                    val idleWeights = idle.update(dtMs, speaking)
                    val silentVisemes = visemeMapper.map(features, prosody)
                    for (i in 0 until ARKit.COUNT) {
                        physics.setTarget(i, maxOf(silentVisemes[i], idleWeights[i]))
                    }
                }

                // 6. Physics step: spring-mass-damper produces smooth output
                val resolved = physics.update(dtMs)

                // 7. Emit render state
                val state = AvatarRenderState(
                    morphWeights = resolved.copyOf()
                )
                _renderState.value = state
                _emotion.value = EmotionalProsody().also {
                    it.valence = prosody.valence
                    it.arousal = prosody.arousal
                    it.thoughtfulness = prosody.thoughtfulness
                }

                // Target ~60fps render loop
                delay(16)
            }
        }
    }

    override fun stop() {
        running = false
        animJob?.cancel()
        audioQueue.clear()
        physics.reset()
    }
}