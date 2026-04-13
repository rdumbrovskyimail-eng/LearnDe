package com.codeextractor.app.domain.avatar

/**
 * CoArticulator v2 — velocity-aware + per-region smoothing (Claude).
 *
 * Gemini предлагал удалить CoArticulator (только физика).
 * Но физика сглаживает индивидуальные blend shapes,
 * а ко-артикуляция — это ВРЕМЕННОЕ смешивание между фонемами.
 * Они решают разные задачи.
 *
 * Решение: оставляем, но с velocity gating —
 * при высокой скорости (взрывные P/B) carry минимален,
 * при медленных переходах (гласные) carry полный.
 */
class CoArticulator(
    private val historySize: Int = 5,
) {
    private val history = Array(historySize) { FloatArray(ARKit.COUNT) }
    private var writeIdx = 0
    private var filled = 0
    private val output = FloatArray(ARKit.COUNT)
    private val velocity = FloatArray(ARKit.COUNT)

    // Per-region carry weights (какие blend shapes сглаживать сильнее)
    private val regionCarry = FloatArray(ARKit.COUNT)

    init { initRegionWeights() }

    fun process(rawWeights: FloatArray): FloatArray {
        val currentIdx = writeIdx % historySize

        // Velocity: как быстро меняется каждый blend shape
        if (filled > 0) {
            val prevIdx = ((writeIdx - 1) % historySize + historySize) % historySize
            for (i in 0 until ARKit.COUNT) {
                velocity[i] = kotlin.math.abs(rawWeights[i] - history[prevIdx][i])
            }
        }

        System.arraycopy(rawWeights, 0, history[currentIdx], 0, ARKit.COUNT)
        writeIdx++
        filled = minOf(filled + 1, historySize)

        if (filled < 2) {
            System.arraycopy(rawWeights, 0, output, 0, ARKit.COUNT)
            return output
        }

        val prevIdx = ((writeIdx - 2) % historySize + historySize) % historySize
        val prev = history[prevIdx]

        val prev2Idx = ((writeIdx - 3) % historySize + historySize) % historySize
        val prev2 = if (filled >= 3) history[prev2Idx] else prev

        for (i in 0 until ARKit.COUNT) {
            // Высокая velocity → меньше carry (взрывные не размазываются)
            val velFactor = (1f - velocity[i] * 3.5f).coerceIn(0.05f, 1f)
            val carry = regionCarry[i] * velFactor
            val lead = 0.04f * velFactor

            val trend = (2f * prev[i] - prev2[i]).coerceIn(0f, 1f)
            val mainWeight = 1f - carry - lead

            output[i] = rawWeights[i] * mainWeight +
                    prev[i] * carry +
                    trend * lead

            output[i] = output[i].coerceIn(0f, 1f)
        }

        return output
    }

    fun reset() {
        history.forEach { it.fill(0f) }
        writeIdx = 0; filled = 0
        output.fill(0f); velocity.fill(0f)
    }

    private fun initRegionWeights() {
        regionCarry.fill(0.18f)

        // Челюсть: высокая инерция (тяжёлая кость)
        regionCarry[ARKit.JawOpen] = 0.32f
        regionCarry[ARKit.JawForward] = 0.28f
        regionCarry[ARKit.JawLeft] = 0.28f
        regionCarry[ARKit.JawRight] = 0.28f

        // Губные мышцы при согласных: минимальный carry (P, B = мгновенно)
        intArrayOf(
            ARKit.MouthClose, ARKit.MouthPucker,
            ARKit.MouthPressLeft, ARKit.MouthPressRight,
        ).forEach { regionCarry[it] = 0.08f }

        // Уголки рта
        intArrayOf(
            ARKit.MouthSmileLeft, ARKit.MouthSmileRight,
            ARKit.MouthFrownLeft, ARKit.MouthFrownRight,
            ARKit.MouthStretchLeft, ARKit.MouthStretchRight,
            ARKit.MouthDimpleLeft, ARKit.MouthDimpleRight,
        ).forEach { regionCarry[it] = 0.16f }

        // Rounded lips (O, SH): средняя инерция
        regionCarry[ARKit.MouthFunnel] = 0.22f

        // Roll
        regionCarry[ARKit.MouthRollLower] = 0.2f
        regionCarry[ARKit.MouthRollUpper] = 0.2f

        // Брови: медленные (эмоции накатывают плавно)
        intArrayOf(
            ARKit.BrowDownLeft, ARKit.BrowDownRight, ARKit.BrowInnerUp,
            ARKit.BrowOuterUpLeft, ARKit.BrowOuterUpRight,
        ).forEach { regionCarry[it] = 0.33f }

        // Глаза: мгновенные
        intArrayOf(
            ARKit.EyeBlinkLeft, ARKit.EyeBlinkRight,
            ARKit.EyeSquintLeft, ARKit.EyeSquintRight,
            ARKit.EyeWideLeft, ARKit.EyeWideRight,
            ARKit.EyeLookDownLeft, ARKit.EyeLookInLeft, ARKit.EyeLookOutLeft, ARKit.EyeLookUpLeft,
            ARKit.EyeLookDownRight, ARKit.EyeLookInRight, ARKit.EyeLookOutRight, ARKit.EyeLookUpRight,
        ).forEach { regionCarry[it] = 0.04f }

        // Щёки, нос: мясистая ткань
        intArrayOf(
            ARKit.CheekPuff, ARKit.CheekSquintLeft, ARKit.CheekSquintRight,
        ).forEach { regionCarry[it] = 0.28f }
        regionCarry[ARKit.NoseSneerLeft] = 0.23f
        regionCarry[ARKit.NoseSneerRight] = 0.23f
    }
}
