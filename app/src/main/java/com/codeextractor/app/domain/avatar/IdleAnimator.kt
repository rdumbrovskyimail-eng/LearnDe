package com.codeextractor.app.domain.avatar

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * IdleAnimator v4 — Биологически корректная idle-анимация.
 * Генерирует АДДИТИВНЫЙ слой поверх визем речи.
 * Компоненты: BLINK, MICRO-SACCADES, BREATHING, MICRO-BROW, NOSTRIL FLARE.
 */
class IdleAnimator {

    companion object {
        private const val BLINK_CLOSE_DURATION  = 0.060f
        private const val BLINK_HOLD_DURATION   = 0.055f
        private const val BLINK_OPEN_DURATION   = 0.085f
        private const val BLINK_TOTAL           = BLINK_CLOSE_DURATION + BLINK_HOLD_DURATION + BLINK_OPEN_DURATION
        private const val BLINK_INTERVAL_MIN    = 2.0f
        private const val BLINK_INTERVAL_MAX    = 6.0f
        private const val BLINK_SPEAKING_MULT   = 1.6f
        private const val SACCADE_INTERVAL_MIN  = 1.2f
        private const val SACCADE_INTERVAL_MAX  = 3.5f
        private const val SACCADE_MAGNITUDE     = 0.04f
        private const val SACCADE_SMOOTH        = 8.0f
        private const val BREATH_FREQ           = 0.80f
        private const val BREATH_JAW_AMP        = 0.012f
        private const val BREATH_NOSTRIL_AMP    = 0.014f
        private const val BROW_EVENT_MIN        = 5.0f
        private const val BROW_EVENT_MAX        = 15.0f
        private const val BROW_DURATION         = 0.35f
        private const val BROW_MAX_AMP          = 0.055f
        private const val SACCADE_SNAP          = 0.003f
    }

    private val weights = FloatArray(ARKit.COUNT)
    private var blinkTimer = randomBlinkInterval(false)
    private var blinkPhase = -1f
    private var saccadeTimer = randomSaccadeInterval()
    private var saccadeTargetX = 0f; private var saccadeTargetY = 0f
    private var saccadeCurrentX = 0f; private var saccadeCurrentY = 0f
    private var breathPhase = Random.nextFloat() * (2f * Math.PI.toFloat())
    private var browEventTimer = randomBrowInterval()
    private var browEventPhase = -1f
    private var browEventSide = 0; private var browEventAmp = 0f

    fun update(dtMs: Long, isSpeaking: Boolean): FloatArray {
        val dt = dtMs.coerceIn(1, 32) / 1000f
        weights.fill(0f)
        updateBlink(dt, isSpeaking)
        updateMicroSaccade(dt)
        updateBreathing(dt)
        updateMicroBrow(dt)
        return weights
    }

    fun reset() {
        weights.fill(0f); blinkTimer = randomBlinkInterval(false); blinkPhase = -1f
        saccadeTimer = randomSaccadeInterval()
        saccadeTargetX = 0f; saccadeTargetY = 0f; saccadeCurrentX = 0f; saccadeCurrentY = 0f
        breathPhase = Random.nextFloat() * (2f * Math.PI.toFloat())
        browEventTimer = randomBrowInterval(); browEventPhase = -1f
    }

    private fun updateBlink(dt: Float, speaking: Boolean) {
        if (blinkPhase >= 0f) {
            blinkPhase += dt
            val v = computeBlinkValue(blinkPhase)
            weights[ARKit.EyeBlinkLeft] = v; weights[ARKit.EyeBlinkRight] = v
            if (v > 0.3f) { val sq = (v - 0.3f) * 0.25f; weights[ARKit.EyeSquintLeft] = sq; weights[ARKit.EyeSquintRight] = sq }
            if (blinkPhase >= BLINK_TOTAL) { blinkPhase = -1f; blinkTimer = randomBlinkInterval(speaking) }
        } else { blinkTimer -= dt; if (blinkTimer <= 0f) blinkPhase = 0f }
    }

    private fun computeBlinkValue(phase: Float): Float {
        val ce = BLINK_CLOSE_DURATION; val he = ce + BLINK_HOLD_DURATION
        return when { phase < ce -> { val t = phase / ce; t * t }; phase < he -> 1f; phase < BLINK_TOTAL -> 1f - (phase - he) / BLINK_OPEN_DURATION; else -> 0f }
    }

    private fun updateMicroSaccade(dt: Float) {
        saccadeTimer -= dt
        if (saccadeTimer <= 0f) { saccadeTargetX = (Random.nextFloat() - 0.5f) * 2f * SACCADE_MAGNITUDE; saccadeTargetY = (Random.nextFloat() - 0.5f) * 2f * SACCADE_MAGNITUDE; saccadeTimer = randomSaccadeInterval() }
        val sp = SACCADE_SMOOTH * dt
        saccadeCurrentX += (saccadeTargetX - saccadeCurrentX) * sp; saccadeCurrentY += (saccadeTargetY - saccadeCurrentY) * sp
        if (abs(saccadeCurrentX - saccadeTargetX) < SACCADE_SNAP) saccadeCurrentX = saccadeTargetX
        if (abs(saccadeCurrentY - saccadeTargetY) < SACCADE_SNAP) saccadeCurrentY = saccadeTargetY
        val ax = abs(saccadeCurrentX).coerceIn(0f, 1f); val ay = abs(saccadeCurrentY).coerceIn(0f, 1f)
        if (saccadeCurrentX > 0f) { weights[ARKit.EyeLookOutLeft] = ax; weights[ARKit.EyeLookInRight] = ax } else { weights[ARKit.EyeLookInLeft] = ax; weights[ARKit.EyeLookOutRight] = ax }
        if (saccadeCurrentY > 0f) { weights[ARKit.EyeLookUpLeft] = ay; weights[ARKit.EyeLookUpRight] = ay } else { weights[ARKit.EyeLookDownLeft] = ay; weights[ARKit.EyeLookDownRight] = ay }
    }

    private fun updateBreathing(dt: Float) {
        breathPhase += dt * BREATH_FREQ * (2f * Math.PI.toFloat())
        val bn = (sin(breathPhase) * 0.5f + 0.5f)
        weights[ARKit.JawOpen] = bn * BREATH_JAW_AMP
        val nf = bn * BREATH_NOSTRIL_AMP; weights[ARKit.NoseSneerLeft] = nf; weights[ARKit.NoseSneerRight] = nf
    }

    private fun updateMicroBrow(dt: Float) {
        if (browEventPhase >= 0f) {
            browEventPhase += dt; val t = browEventPhase / BROW_DURATION
            val bv = when { t < 0.4f -> browEventAmp * (t / 0.4f); t < 0.7f -> browEventAmp; t < 1.0f -> browEventAmp * ((1f - t) / 0.3f); else -> { browEventPhase = -1f; 0f } }
            when (browEventSide) { 1 -> { weights[ARKit.BrowInnerUp] = bv * 0.7f; weights[ARKit.BrowOuterUpLeft] = bv * 0.4f }; 2 -> { weights[ARKit.BrowInnerUp] = bv * 0.7f; weights[ARKit.BrowOuterUpRight] = bv * 0.4f }; else -> { weights[ARKit.BrowInnerUp] = bv; weights[ARKit.BrowOuterUpLeft] = bv * 0.5f; weights[ARKit.BrowOuterUpRight] = bv * 0.5f } }
        } else { browEventTimer -= dt; if (browEventTimer <= 0f) { browEventPhase = 0f; browEventAmp = Random.nextFloat() * BROW_MAX_AMP; browEventSide = when (Random.nextInt(4)) { 0 -> 1; 1 -> 2; else -> 0 }; browEventTimer = randomBrowInterval() } }
    }

    private fun randomBlinkInterval(s: Boolean): Float { val b = BLINK_INTERVAL_MIN + Random.nextFloat() * (BLINK_INTERVAL_MAX - BLINK_INTERVAL_MIN); return if (s) b * BLINK_SPEAKING_MULT else b }
    private fun randomSaccadeInterval() = SACCADE_INTERVAL_MIN + Random.nextFloat() * (SACCADE_INTERVAL_MAX - SACCADE_INTERVAL_MIN)
    private fun randomBrowInterval() = BROW_EVENT_MIN + Random.nextFloat() * (BROW_EVENT_MAX - BROW_EVENT_MIN)
}
