package com.codeextractor.app.domain.avatar

import kotlin.math.sin
import kotlin.random.Random

class IdleAnimator {

    private var blinkTimer = randomBlinkInterval()
    private var blinkPhase = -1f // -1 = not blinking
    private var saccadeTimer = randomSaccadeInterval()
    private var saccadeX = 0f
    private var saccadeY = 0f
    private var breathPhase = 0f
    private var microBrowTimer = Random.nextFloat() * 8f + 5f

    private val weights = FloatArray(ARKit.COUNT)

    /** Returns additive idle weights. Caller should max() with speech weights. */
    fun update(dtMs: Long, isSpeaking: Boolean): FloatArray {
        val dt = dtMs / 1000f
        weights.fill(0f)

        // ─── BLINK ───
        blinkTimer -= dt
        if (blinkPhase >= 0f) {
            blinkPhase += dt
            val blinkVal = when {
                blinkPhase < 0.06f -> blinkPhase / 0.06f          // close
                blinkPhase < 0.12f -> 1f                           // hold
                blinkPhase < 0.20f -> 1f - (blinkPhase - 0.12f) / 0.08f // open
                else -> { blinkPhase = -1f; 0f }
            }
            weights[ARKit.EyeBlinkLeft] = blinkVal
            weights[ARKit.EyeBlinkRight] = blinkVal
        } else if (blinkTimer <= 0f) {
            blinkPhase = 0f
            blinkTimer = randomBlinkInterval() * if (isSpeaking) 1.5f else 1f
        }

        // ─── MICRO-SACCADES ───
        saccadeTimer -= dt
        if (saccadeTimer <= 0f) {
            saccadeX = (Random.nextFloat() - 0.5f) * 0.12f
            saccadeY = (Random.nextFloat() - 0.5f) * 0.08f
            saccadeTimer = randomSaccadeInterval()
        }
        // Smooth toward current saccade target
        val sx = saccadeX; val sy = saccadeY
        if (sx > 0f) {
            weights[ARKit.EyeLookOutLeft] = sx; weights[ARKit.EyeLookInRight] = sx
        } else {
            weights[ARKit.EyeLookInLeft] = -sx; weights[ARKit.EyeLookOutRight] = -sx
        }
        if (sy > 0f) {
            weights[ARKit.EyeLookUpLeft] = sy; weights[ARKit.EyeLookUpRight] = sy
        } else {
            weights[ARKit.EyeLookDownLeft] = -sy; weights[ARKit.EyeLookDownRight] = -sy
        }

        // ─── BREATHING ───
        breathPhase += dt * 0.8f // ~4 sec cycle
        val breath = (sin(breathPhase * Math.PI.toFloat() * 2f) * 0.5f + 0.5f) * 0.015f
        weights[ARKit.JawOpen] = breath

        // ─── MICRO BROW ───
        microBrowTimer -= dt
        if (microBrowTimer <= 0f) {
            microBrowTimer = Random.nextFloat() * 10f + 5f
        }
        if (microBrowTimer < 0.5f) {
            weights[ARKit.BrowInnerUp] = (0.5f - microBrowTimer) * 0.08f
        }

        return weights
    }

    private fun randomBlinkInterval() = Random.nextFloat() * 4f + 2f   // 2-6 sec
    private fun randomSaccadeInterval() = Random.nextFloat() * 1.5f + 0.5f // 0.5-2 sec
}