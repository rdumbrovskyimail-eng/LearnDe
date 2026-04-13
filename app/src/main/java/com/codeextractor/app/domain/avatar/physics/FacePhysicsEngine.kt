package com.codeextractor.app.domain.avatar.physics

import com.codeextractor.app.domain.avatar.ARKit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Биомеханический симулятор лицевых тканей.
 *
 * Claude: ζ damping ratios рассчитаны по формуле ζ = d / (2√k).
 * Gemini: анатомические группы мышц с правильной массой.
 *
 * ζ < 1: underdamped (мягкие ткани — щёки, брови)
 * ζ ≈ 1: critically damped (точные движения — веки, губные смыкания)
 * ζ > 1: overdamped (тяжёлые части — челюсть)
 */
class FacePhysicsEngine {

    private val values = FloatArray(ARKit.COUNT)
    private val velocities = FloatArray(ARKit.COUNT)
    private val targets = FloatArray(ARKit.COUNT)
    private val stiffness = FloatArray(ARKit.COUNT)
    private val damping = FloatArray(ARKit.COUNT)
    private val output = FloatArray(ARKit.COUNT)
    private val maxOvershoot = FloatArray(ARKit.COUNT)

    init { initTissueProfiles() }

    fun setTarget(idx: Int, weight: Float) {
        if (idx in 0 until ARKit.COUNT) {
            targets[idx] = weight.coerceIn(0f, 1f)
        }
    }

    fun setTargets(weights: FloatArray) {
        val n = min(weights.size, ARKit.COUNT)
        System.arraycopy(weights, 0, targets, 0, n)
    }

    fun update(dtMs: Long): FloatArray {
        val dt = min(32f, max(1f, dtMs.toFloat())) / 1000f

        for (i in 0 until ARKit.COUNT) {
            val spring = stiffness[i]
            val damp = damping[i]
            val target = targets[i]
            val error = target - values[i]

            // Semi-implicit Euler (стабильнее explicit)
            val accel = error * spring - velocities[i] * damp
            velocities[i] += accel * dt
            var v = values[i] + velocities[i] * dt

            // Soft boundaries
            val maxOS = maxOvershoot[i]
            if (v < -0.02f) {
                v = -0.02f
                velocities[i] = -velocities[i] * 0.05f
            }
            if (v > 1f + maxOS) {
                v = 1f + maxOS
                velocities[i] = -velocities[i] * 0.05f
            }

            values[i] = v
            output[i] = v.coerceIn(0f, 1f)

            // Snap при сходимости (убирает микро-осцилляции)
            if (kotlin.math.abs(error) < 0.002f && kotlin.math.abs(velocities[i]) < 0.01f) {
                values[i] = target
                velocities[i] = 0f
                output[i] = target.coerceIn(0f, 1f)
            }
        }
        return output
    }

    fun snapToTargets() {
        System.arraycopy(targets, 0, values, 0, ARKit.COUNT)
        velocities.fill(0f)
        System.arraycopy(targets, 0, output, 0, ARKit.COUNT)
    }

    fun reset() {
        values.fill(0f); velocities.fill(0f); targets.fill(0f); output.fill(0f)
    }

    private fun initTissueProfiles() {
        // ═══ DEFAULTS: средняя мышечная ткань ═══
        stiffness.fill(200f); damping.fill(22f); maxOvershoot.fill(0.08f)

        // ═══ EYELIDS: critically damped, fastest muscles (Gemini + Claude) ═══
        intArrayOf(ARKit.EyeBlinkLeft, ARKit.EyeBlinkRight).forEach {
            stiffness[it] = 550f
            damping[it] = 2f * sqrt(550f) // ζ ≈ 1.0
            maxOvershoot[it] = 0.02f
        }
        intArrayOf(ARKit.EyeSquintLeft, ARKit.EyeSquintRight,
            ARKit.EyeWideLeft, ARKit.EyeWideRight).forEach {
            stiffness[it] = 400f; damping[it] = 35f; maxOvershoot[it] = 0.03f
        }

        // ═══ PUPILS: fastest in human body ═══
        intArrayOf(
            ARKit.EyeLookDownLeft, ARKit.EyeLookInLeft, ARKit.EyeLookOutLeft, ARKit.EyeLookUpLeft,
            ARKit.EyeLookDownRight, ARKit.EyeLookInRight, ARKit.EyeLookOutRight, ARKit.EyeLookUpRight
        ).forEach {
            stiffness[it] = 700f; damping[it] = 2f * sqrt(700f); maxOvershoot[it] = 0.01f
        }

        // ═══ JAW: heavy bone, overdamped (Gemini) ═══
        intArrayOf(ARKit.JawOpen, ARKit.JawForward, ARKit.JawLeft, ARKit.JawRight).forEach {
            stiffness[it] = 150f; damping[it] = 20f // ζ ≈ 0.82 (slight underdamp for weight feel)
            maxOvershoot[it] = 0.1f
        }

        // ═══ LIP CLOSURE: bilabials P/B/M — near-critical (Gemini: must snap shut) ═══
        intArrayOf(ARKit.MouthClose, ARKit.MouthPressLeft, ARKit.MouthPressRight).forEach {
            stiffness[it] = 420f
            damping[it] = 2f * sqrt(420f) * 0.92f // ζ ≈ 0.92
            maxOvershoot[it] = 0.03f
        }

        // ═══ LIP ROUNDING: orbicularis oris (OO, SH) ═══
        intArrayOf(ARKit.MouthPucker, ARKit.MouthFunnel).forEach {
            stiffness[it] = 240f; damping[it] = 25f; maxOvershoot[it] = 0.06f
        }

        // ═══ LIP STRETCH: risorius/zygomaticus (EE, smile) ═══
        intArrayOf(ARKit.MouthStretchLeft, ARKit.MouthStretchRight,
            ARKit.MouthSmileLeft, ARKit.MouthSmileRight).forEach {
            stiffness[it] = 260f; damping[it] = 26f; maxOvershoot[it] = 0.06f
        }

        // ═══ LIP ROLL (F/V sounds) ═══
        intArrayOf(ARKit.MouthRollLower, ARKit.MouthRollUpper).forEach {
            stiffness[it] = 300f; damping[it] = 28f; maxOvershoot[it] = 0.04f
        }

        // ═══ LIP VERTICAL ═══
        intArrayOf(ARKit.MouthLowerDownLeft, ARKit.MouthLowerDownRight,
            ARKit.MouthUpperUpLeft, ARKit.MouthUpperUpRight).forEach {
            stiffness[it] = 210f; damping[it] = 21f; maxOvershoot[it] = 0.05f
        }

        // ═══ SHRUG ═══
        intArrayOf(ARKit.MouthShrugLower, ARKit.MouthShrugUpper).forEach {
            stiffness[it] = 190f; damping[it] = 20f; maxOvershoot[it] = 0.04f
        }

        // ═══ FROWN: slow emotional ═══
        intArrayOf(ARKit.MouthFrownLeft, ARKit.MouthFrownRight).forEach {
            stiffness[it] = 130f; damping[it] = 17f; maxOvershoot[it] = 0.05f
        }

        // ═══ DIMPLES ═══
        intArrayOf(ARKit.MouthDimpleLeft, ARKit.MouthDimpleRight).forEach {
            stiffness[it] = 170f; damping[it] = 19f; maxOvershoot[it] = 0.04f
        }

        // ═══ BROWS: frontalis + corrugator, slow emotional (Gemini: soft tissue) ═══
        intArrayOf(ARKit.BrowDownLeft, ARKit.BrowDownRight, ARKit.BrowInnerUp,
            ARKit.BrowOuterUpLeft, ARKit.BrowOuterUpRight).forEach {
            stiffness[it] = 115f; damping[it] = 16f; maxOvershoot[it] = 0.08f
        }

        // ═══ CHEEKS: buccinator, heavy tissue (Gemini) ═══
        intArrayOf(ARKit.CheekPuff, ARKit.CheekSquintLeft, ARKit.CheekSquintRight).forEach {
            stiffness[it] = 95f; damping[it] = 15f; maxOvershoot[it] = 0.1f
        }

        // ═══ NOSE ═══
        intArrayOf(ARKit.NoseSneerLeft, ARKit.NoseSneerRight).forEach {
            stiffness[it] = 125f; damping[it] = 16f; maxOvershoot[it] = 0.05f
        }
    }
}
