package com.learnde.app.domain.avatar

import kotlin.math.abs
import kotlin.math.min

/**
 * CoArticulator v4 — Velocity-Aware Temporal Blending
 *
 * Решает задачу ВРЕМЕННОГО ПЕРЕКРЫТИЯ ФОНЕМ (carry + lead).
 * FacePhysicsEngine сглаживает индивидуальные blendshapes,
 * CoArticulator размывает переходы между фонемами.
 */
class CoArticulator(private val historySize: Int = 4) {

    companion object {
        private const val DEFAULT_CARRY  = 0.17f
        private const val LEAD_BASE      = 0.035f
        private const val VEL_SENSITIVITY = 3.2f
        private const val VEL_MIN_FACTOR  = 0.05f
        private const val VEL_SMOOTH      = 0.40f

        private const val CARRY_JAW        = 0.30f
        private const val CARRY_LIP_SEAL   = 0.06f
        private const val CARRY_LIP_ROUND  = 0.20f
        private const val CARRY_LIP_STRCH  = 0.15f
        private const val CARRY_LIP_VERT   = 0.14f
        private const val CARRY_LIP_ROLL   = 0.18f
        private const val CARRY_FROWN      = 0.28f
        private const val CARRY_DIMPLE     = 0.14f
        private const val CARRY_BROWS      = 0.33f
        private const val CARRY_EYES       = 0.03f
        private const val CARRY_CHEEKS     = 0.28f
        private const val CARRY_NOSE       = 0.22f
    }

    private val history  = Array(historySize) { FloatArray(ARKit.COUNT) }
    private var ringPos  = 0
    private var filled   = 0
    private val output   = FloatArray(ARKit.COUNT)
    private val velocity = FloatArray(ARKit.COUNT)
    private val regionCarry = FloatArray(ARKit.COUNT)

    init { initRegionWeights() }

    fun process(rawWeights: FloatArray): FloatArray {
        val curIdx  = ringPos % historySize
        val prevIdx = (ringPos - 1 + historySize) % historySize
        val prev2Idx = (ringPos - 2 + historySize) % historySize

        if (filled > 0) {
            val prev = history[prevIdx]
            for (i in 0 until ARKit.COUNT) {
                val iv = abs(rawWeights[i] - prev[i])
                velocity[i] = velocity[i] * (1f - VEL_SMOOTH) + iv * VEL_SMOOTH
            }
        }

        rawWeights.copyInto(history[curIdx], endIndex = ARKit.COUNT)
        ringPos++
        filled = min(filled + 1, historySize)

        if (filled < 2) { rawWeights.copyInto(output, endIndex = ARKit.COUNT); return output }

        val prev  = history[prevIdx]
        val prev2 = if (filled >= 3) history[prev2Idx] else prev

        for (i in 0 until ARKit.COUNT) {
            val vf = (1f - velocity[i] * VEL_SENSITIVITY).coerceIn(VEL_MIN_FACTOR, 1f)
            val carry = regionCarry[i] * vf
            val lead  = LEAD_BASE * vf
            val trend = (2f * prev[i] - prev2[i]).coerceIn(0f, 1f)
            val mw = (1f - carry - lead).coerceAtLeast(0f)
            output[i] = (rawWeights[i] * mw + prev[i] * carry + trend * lead).coerceIn(0f, 1f)
        }
        return output
    }

    fun reset() {
        for (buf in history) buf.fill(0f)
        ringPos = 0; filled = 0; output.fill(0f); velocity.fill(0f)
    }

    private fun initRegionWeights() {
        regionCarry.fill(DEFAULT_CARRY)
        for (i in ARKit.GROUP_JAW) regionCarry[i] = CARRY_JAW
        for (i in ARKit.GROUP_LIP_SEAL) regionCarry[i] = CARRY_LIP_SEAL
        for (i in ARKit.GROUP_LIP_ROUND) regionCarry[i] = CARRY_LIP_ROUND
        for (i in ARKit.GROUP_LIP_STRETCH) regionCarry[i] = CARRY_LIP_STRCH
        for (i in ARKit.GROUP_LIP_VERTICAL) regionCarry[i] = CARRY_LIP_VERT
        regionCarry[ARKit.MouthRollLower] = CARRY_LIP_ROLL
        regionCarry[ARKit.MouthRollUpper] = CARRY_LIP_ROLL
        regionCarry[ARKit.MouthShrugLower] = CARRY_LIP_ROLL
        regionCarry[ARKit.MouthShrugUpper] = CARRY_LIP_ROLL
        regionCarry[ARKit.MouthFrownLeft] = CARRY_FROWN
        regionCarry[ARKit.MouthFrownRight] = CARRY_FROWN
        regionCarry[ARKit.MouthDimpleLeft] = CARRY_DIMPLE
        regionCarry[ARKit.MouthDimpleRight] = CARRY_DIMPLE
        for (i in ARKit.GROUP_BROWS) regionCarry[i] = CARRY_BROWS
        for (i in ARKit.GROUP_EYELIDS) regionCarry[i] = CARRY_EYES
        for (i in ARKit.GROUP_PUPILS) regionCarry[i] = CARRY_EYES
        regionCarry[ARKit.CheekPuff] = CARRY_CHEEKS
        regionCarry[ARKit.CheekSquintLeft] = CARRY_CHEEKS
        regionCarry[ARKit.CheekSquintRight] = CARRY_CHEEKS
        regionCarry[ARKit.NoseSneerLeft] = CARRY_NOSE
        regionCarry[ARKit.NoseSneerRight] = CARRY_NOSE
    }
}
