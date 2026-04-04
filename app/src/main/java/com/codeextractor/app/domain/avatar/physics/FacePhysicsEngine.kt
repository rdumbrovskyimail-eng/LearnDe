package com.codeextractor.app.domain.avatar.physics

import com.codeextractor.app.domain.avatar.ARKit
import kotlin.math.max
import kotlin.math.min

class FacePhysicsEngine {

    private val values = FloatArray(ARKit.COUNT)
    private val velocities = FloatArray(ARKit.COUNT)
    private val targets = FloatArray(ARKit.COUNT)
    private val stiffness = FloatArray(ARKit.COUNT)
    private val damping = FloatArray(ARKit.COUNT)
    private val output = FloatArray(ARKit.COUNT)

    init { initTissueProfiles() }

    fun setTarget(idx: Int, weight: Float) {
        if (idx in 0 until ARKit.COUNT) {
            targets[idx] = weight.coerceIn(0f, 1f)
        }
    }

    /** Batch set targets from a full array */
    fun setTargets(weights: FloatArray) {
        val n = min(weights.size, ARKit.COUNT)
        System.arraycopy(weights, 0, targets, 0, n)
    }

    /** Call every frame. Returns clamped [0,1] weights for Filament. */
    fun update(dtMs: Long): FloatArray {
        val dt = min(32f, max(1f, dtMs.toFloat())) / 1000f

        for (i in 0 until ARKit.COUNT) {
            val spring = stiffness[i]
            val damp = damping[i]
            val tension = (targets[i] - values[i]) * spring
            val dampForce = velocities[i] * damp
            val accel = tension - dampForce

            velocities[i] += accel * dt
            var v = values[i] + velocities[i] * dt

            // Soft bounce at boundaries
            if (v < 0f) { v = 0f; velocities[i] = -velocities[i] * 0.08f }
            if (v > 1.15f) { v = 1.15f; velocities[i] = 0f }

            values[i] = v
            output[i] = v.coerceIn(0f, 1f)
        }
        return output
    }

    /** Instant snap (for interruptions) */
    fun snapToTargets() {
        System.arraycopy(targets, 0, values, 0, ARKit.COUNT)
        velocities.fill(0f)
        System.arraycopy(targets, 0, output, 0, ARKit.COUNT)
    }

    fun reset() {
        values.fill(0f); velocities.fill(0f); targets.fill(0f); output.fill(0f)
    }

    private fun initTissueProfiles() {
        // Defaults: medium muscle
        stiffness.fill(180f); damping.fill(20f)

        // Eyelids: instant, critically damped
        intArrayOf(ARKit.EyeBlinkLeft, ARKit.EyeBlinkRight,
            ARKit.EyeSquintLeft, ARKit.EyeSquintRight,
            ARKit.EyeWideLeft, ARKit.EyeWideRight
        ).forEach { stiffness[it] = 450f; damping[it] = 40f }

        // Pupils: near-instant
        intArrayOf(ARKit.EyeLookDownLeft, ARKit.EyeLookInLeft, ARKit.EyeLookOutLeft, ARKit.EyeLookUpLeft,
            ARKit.EyeLookDownRight, ARKit.EyeLookInRight, ARKit.EyeLookOutRight, ARKit.EyeLookUpRight
        ).forEach { stiffness[it] = 600f; damping[it] = 42f }

        // Jaw: heavy bone, slow start, smooth stop
        intArrayOf(ARKit.JawOpen, ARKit.JawForward, ARKit.JawLeft, ARKit.JawRight)
            .forEach { stiffness[it] = 120f; damping[it] = 16f }

        // Lip snaps (M, B, P): fast with slight bounce
        intArrayOf(ARKit.MouthClose, ARKit.MouthPucker, ARKit.MouthPressLeft, ARKit.MouthPressRight)
            .forEach { stiffness[it] = 300f; damping[it] = 22f }

        // Brows, cheeks, smile: soft fleshy tissue, slow settle
        intArrayOf(ARKit.CheekPuff, ARKit.CheekSquintLeft, ARKit.CheekSquintRight,
            ARKit.BrowDownLeft, ARKit.BrowDownRight, ARKit.BrowInnerUp,
            ARKit.BrowOuterUpLeft, ARKit.BrowOuterUpRight,
            ARKit.MouthSmileLeft, ARKit.MouthSmileRight,
            ARKit.NoseSneerLeft, ARKit.NoseSneerRight
        ).forEach { stiffness[it] = 100f; damping[it] = 15f }
    }
}