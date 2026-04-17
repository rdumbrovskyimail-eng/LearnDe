// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/domain/avatar/physics/FacePhysicsEngine.kt
//
// ФИКС:
//   • В конце update() каждый output sanitize-ится: NaN/Infinity → 0f.
//   • Дополнительно — защита в velocity-обновлении: если velocity или
//     position ушли в non-finite (например, при экстремально больших
//     target), сбрасываем в 0.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.domain.avatar.physics

import com.learnde.app.domain.avatar.ARKit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class FacePhysicsEngine {

    companion object {
        private const val SNAP_ERROR_THRESHOLD = 0.002f
        private const val SNAP_VEL_THRESHOLD   = 0.010f

        private const val LOWER_BOUND         = -0.02f
        private const val LOWER_BOUNCE        =  0.05f
        private const val UPPER_BOUNCE        =  0.05f

        private const val FAST_K    = 580f
        private val       FAST_D    = 2f * sqrt(FAST_K) * 1.00f
        private const val FAST_OS   = 0.01f

        private const val BONE_K    = 155f
        private val       BONE_D    = 2f * sqrt(BONE_K) * 0.82f
        private const val BONE_OS   = 0.10f

        private const val SPHX_K    = 430f
        private val       SPHX_D    = 2f * sqrt(SPHX_K) * 0.92f
        private const val SPHX_OS   = 0.03f

        private const val MUSC_K    = 240f
        private val       MUSC_D    = 2f * sqrt(MUSC_K) * 0.85f
        private const val MUSC_OS   = 0.06f

        private const val SOFT_K    = 110f
        private val       SOFT_D    = 2f * sqrt(SOFT_K) * 0.62f
        private const val SOFT_OS   = 0.10f

        private const val SLOW_K    = 125f
        private val       SLOW_D    = 2f * sqrt(SLOW_K) * 0.78f
        private const val SLOW_OS   = 0.06f
    }

    private val position    = FloatArray(ARKit.COUNT)
    private val velocity    = FloatArray(ARKit.COUNT)
    private val target      = FloatArray(ARKit.COUNT)
    private val output      = FloatArray(ARKit.COUNT)

    private val stiffness   = FloatArray(ARKit.COUNT)
    private val damping     = FloatArray(ARKit.COUNT)
    private val maxOvershoot = FloatArray(ARKit.COUNT)

    init { initTissueProfiles() }

    fun setTarget(idx: Int, weight: Float) {
        if (idx in 0 until ARKit.COUNT) {
            val w = if (weight.isFinite()) weight.coerceIn(0f, 1f) else 0f
            target[idx] = w
        }
    }

    fun setTargets(weights: FloatArray) {
        val n = min(weights.size, ARKit.COUNT)
        for (i in 0 until n) {
            val w = weights[i]
            target[i] = if (w.isFinite()) w.coerceIn(0f, 1f) else 0f
        }
    }

    fun update(dtMs: Long): FloatArray {
        val dt = min(32f, dtMs.toFloat().coerceAtLeast(1f)) / 1000f

        for (i in 0 until ARKit.COUNT) {
            val k    = stiffness[i]
            val d    = damping[i]
            val tgt  = target[i]
            val err  = tgt - position[i]

            val accel = err * k - velocity[i] * d
            velocity[i] += accel * dt
            var pos = position[i] + velocity[i] * dt

            // Safety: если ушли в non-finite — жёсткий reset индекса
            if (!pos.isFinite() || !velocity[i].isFinite()) {
                pos = 0f
                velocity[i] = 0f
            }

            if (pos < LOWER_BOUND) {
                pos = LOWER_BOUND
                velocity[i] = -velocity[i] * LOWER_BOUNCE
            }

            val upperBound = 1f + maxOvershoot[i]
            if (pos > upperBound) {
                pos = upperBound
                velocity[i] = -velocity[i] * UPPER_BOUNCE
            }

            position[i] = pos

            if (abs(err) < SNAP_ERROR_THRESHOLD &&
                abs(velocity[i]) < SNAP_VEL_THRESHOLD) {
                position[i] = tgt
                velocity[i] = 0f
            }

            val out = position[i]
            output[i] = if (out.isFinite()) out.coerceIn(0f, 1f) else 0f
        }

        return output
    }

    fun snapToTargets() {
        target.copyInto(position)
        velocity.fill(0f)
        for (i in 0 until ARKit.COUNT) {
            val p = position[i]
            output[i] = if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
        }
    }

    fun reset() {
        position.fill(0f)
        velocity.fill(0f)
        target.fill(0f)
        output.fill(0f)
    }

    private fun initTissueProfiles() {
        stiffness.fill(MUSC_K)
        damping.fill(MUSC_D)
        maxOvershoot.fill(MUSC_OS)

        applyProfile(ARKit.GROUP_EYELIDS, FAST_K, FAST_D, FAST_OS)
        applyProfile(ARKit.GROUP_PUPILS, FAST_K * 1.2f, FAST_D * 1.1f, 0.01f)
        applyProfile(ARKit.GROUP_JAW, BONE_K, BONE_D, BONE_OS)
        applyProfile(ARKit.GROUP_LIP_SEAL, SPHX_K, SPHX_D, SPHX_OS)
        applyProfile(ARKit.GROUP_LIP_ROUND, MUSC_K, MUSC_D, MUSC_OS)
        applyProfile(ARKit.GROUP_LIP_STRETCH, MUSC_K * 1.1f, MUSC_D * 1.05f, MUSC_OS)
        applyProfile(ARKit.GROUP_LIP_VERTICAL, MUSC_K, MUSC_D, MUSC_OS)
        applyProfile(ARKit.GROUP_BROWS, SOFT_K, SOFT_D, SOFT_OS)
        applyProfile(ARKit.GROUP_CHEEKS_NOSE, SOFT_K * 0.9f, SOFT_D * 0.95f, SOFT_OS)

        applyProfile(
            intArrayOf(ARKit.MouthFrownLeft, ARKit.MouthFrownRight),
            SLOW_K, SLOW_D, SLOW_OS,
        )
        applyProfile(
            intArrayOf(ARKit.MouthRight, ARKit.MouthLeft),
            MUSC_K * 0.9f, MUSC_D, MUSC_OS,
        )
        applyProfile(
            intArrayOf(ARKit.MouthRollLower, ARKit.MouthRollUpper),
            280f, 2f * sqrt(280f) * 0.88f, 0.05f,
        )
        applyProfile(
            intArrayOf(ARKit.MouthShrugLower, ARKit.MouthShrugUpper),
            195f, 2f * sqrt(195f) * 0.84f, 0.05f,
        )
        applyProfile(
            intArrayOf(ARKit.MouthDimpleLeft, ARKit.MouthDimpleRight),
            175f, 2f * sqrt(175f) * 0.80f, 0.05f,
        )
        applyProfile(
            intArrayOf(ARKit.CheekPuff),
            85f, 2f * sqrt(85f) * 0.72f, 0.12f,
        )
    }

    private fun applyProfile(
        indices: IntArray,
        k: Float,
        d: Float,
        os: Float,
    ) {
        for (idx in indices) {
            if (idx in 0 until ARKit.COUNT) {
                stiffness[idx]    = k
                damping[idx]      = d
                maxOvershoot[idx] = os
            }
        }
    }
}
