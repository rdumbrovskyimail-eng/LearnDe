package com.codeextractor.app.domain.avatar.physics

import com.codeextractor.app.domain.avatar.ARKit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * FacePhysicsEngine v4 — Biomechanical Soft-Tissue Simulator
 *
 * Моделирует 51 ARKit blendshape как независимые пружинно-массовые системы
 * с биомеханически корректными параметрами демпфирования.
 *
 * ФИЗИЧЕСКАЯ МОДЕЛЬ:
 *   Каждый blendshape — это масса на пружине с демпфером (spring-damper).
 *   Уравнение движения (Semi-implicit Euler):
 *     a[t]   = (target - x[t]) * k  -  v[t] * d
 *     v[t+1] = v[t] + a[t] * dt
 *     x[t+1] = x[t] + v[t+1] * dt
 *
 *   Semi-implicit Euler (velocity обновляется до позиции) устойчивее
 *   explicit Euler при больших k и малых dt — не "взрывается" на 60fps.
 *
 * КОЭФФИЦИЕНТ ДЕМПФИРОВАНИЯ ζ (zeta):
 *   ζ = d / (2 * sqrt(k))
 *   ζ < 1  → underdamped  (мягкие ткани: щёки, брови — небольшой overshoot)
 *   ζ ≈ 1  → critically damped (точные мышцы: веки, губное смыкание)
 *   ζ > 1  → overdamped   (тяжёлые структуры: челюсть)
 *
 * ГРУППЫ ТКАНЕЙ (5 классов):
 *   BONE        — челюсть (ζ ≈ 0.82, тяжёлая инерция)
 *   SPHINCTER   — губное смыкание П/Б/М (ζ ≈ 0.92, near-critical)
 *   MUSCLE      — активные мышцы (ζ ≈ 0.85, стандарт)
 *   SOFT        — мягкие ткани: щёки, брови (ζ ≈ 0.65, underdamped)
 *   FAST        — веки, зрачки (ζ ≈ 1.0, critical — самые быстрые)
 *
 * SNAP TO TARGET:
 *   Когда |error| < 0.002 и |velocity| < 0.01 — мгновенная фиксация.
 *   Устраняет микро-осцилляции при приближении к целевому значению.
 *
 * SOFT BOUNDARIES:
 *   Нижняя граница: −0.02 (небольшой отрицательный overshoot разрешён,
 *   чтобы избежать жёсткого "прилипания" к нулю при underdamped тканях).
 *   Верхняя граница: 1.0 + maxOvershoot (биологически корректный overshoot).
 *
 * Zero-allocation: все массивы pre-allocated в init, никаких object creation
 * в hot path (update вызывается 60 раз/сек).
 */
class FacePhysicsEngine {

    companion object {
        // ── Snap thresholds ──────────────────────────────────────────────
        private const val SNAP_ERROR_THRESHOLD = 0.002f
        private const val SNAP_VEL_THRESHOLD   = 0.010f

        // ── Boundary constants ───────────────────────────────────────────
        private const val LOWER_BOUND         = -0.02f
        private const val LOWER_BOUNCE        =  0.05f  // коэф. отскока от нижней границы
        private const val UPPER_BOUNCE        =  0.05f  // коэф. отскока от верхней границы

        // ── Tissue profile presets ───────────────────────────────────────
        //    (stiffness, damping, maxOvershoot)
        //    damping рассчитан как 2 * sqrt(stiffness) * ζ

        // FAST: веки и зрачки — ζ ≈ 1.0 (critically damped)
        private const val FAST_K    = 580f
        private val       FAST_D    = 2f * sqrt(FAST_K) * 1.00f   // ≈ 48.2
        private const val FAST_OS   = 0.01f

        // BONE: челюсть — ζ ≈ 0.82 (slightly underdamped, ощущение массы)
        private const val BONE_K    = 155f
        private val       BONE_D    = 2f * sqrt(BONE_K) * 0.82f   // ≈ 20.4
        private const val BONE_OS   = 0.10f

        // SPHINCTER: губное смыкание П/Б/М — ζ ≈ 0.92
        private const val SPHX_K    = 430f
        private val       SPHX_D    = 2f * sqrt(SPHX_K) * 0.92f   // ≈ 38.1
        private const val SPHX_OS   = 0.03f

        // MUSCLE: активные мышцы (губы, уголки) — ζ ≈ 0.85
        private const val MUSC_K    = 240f
        private val       MUSC_D    = 2f * sqrt(MUSC_K) * 0.85f   // ≈ 26.3
        private const val MUSC_OS   = 0.06f

        // SOFT: мягкие ткани (щёки, брови) — ζ ≈ 0.62 (underdamped)
        private const val SOFT_K    = 110f
        private val       SOFT_D    = 2f * sqrt(SOFT_K) * 0.62f   // ≈ 13.0
        private const val SOFT_OS   = 0.10f

        // SLOW: медленные эмоциональные мышцы (frown) — ζ ≈ 0.78
        private const val SLOW_K    = 125f
        private val       SLOW_D    = 2f * sqrt(SLOW_K) * 0.78f   // ≈ 17.4
        private const val SLOW_OS   = 0.06f
    }

    // ── Simulation state (pre-allocated) ─────────────────────────────────
    private val position    = FloatArray(ARKit.COUNT)
    private val velocity    = FloatArray(ARKit.COUNT)
    private val target      = FloatArray(ARKit.COUNT)
    private val output      = FloatArray(ARKit.COUNT)

    // ── Tissue profiles (pre-allocated, filled in initProfiles) ──────────
    private val stiffness   = FloatArray(ARKit.COUNT)
    private val damping     = FloatArray(ARKit.COUNT)
    private val maxOvershoot = FloatArray(ARKit.COUNT)

    init { initTissueProfiles() }

    // ═══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    fun setTarget(idx: Int, weight: Float) {
        if (idx in 0 until ARKit.COUNT)
            target[idx] = weight.coerceIn(0f, 1f)
    }

    fun setTargets(weights: FloatArray) {
        val n = min(weights.size, ARKit.COUNT)
        for (i in 0 until n) target[i] = weights[i].coerceIn(0f, 1f)
    }

    /**
     * Шаг симуляции. Вызывается каждый кадр из animator coroutine.
     * Возвращает ссылку на внутренний [output] — НЕ копировать без нужды,
     * данные актуальны до следующего вызова update().
     */
    fun update(dtMs: Long): FloatArray {
        val dt = min(32f, dtMs.toFloat().coerceAtLeast(1f)) / 1000f

        for (i in 0 until ARKit.COUNT) {
            val k    = stiffness[i]
            val d    = damping[i]
            val tgt  = target[i]
            val err  = tgt - position[i]

            // Semi-implicit Euler
            val accel = err * k - velocity[i] * d
            velocity[i] += accel * dt
            var pos = position[i] + velocity[i] * dt

            // Soft lower boundary
            if (pos < LOWER_BOUND) {
                pos = LOWER_BOUND
                velocity[i] = -velocity[i] * LOWER_BOUNCE
            }

            // Soft upper boundary
            val upperBound = 1f + maxOvershoot[i]
            if (pos > upperBound) {
                pos = upperBound
                velocity[i] = -velocity[i] * UPPER_BOUNCE
            }

            position[i] = pos

            // Snap to target при сходимости
            if (abs(err) < SNAP_ERROR_THRESHOLD &&
                abs(velocity[i]) < SNAP_VEL_THRESHOLD) {
                position[i] = tgt
                velocity[i] = 0f
            }

            output[i] = position[i].coerceIn(0f, 1f)
        }

        return output
    }

    /** Мгновенный прыжок к текущим целям (при старте сцены, смене аватара). */
    fun snapToTargets() {
        target.copyInto(position)
        velocity.fill(0f)
        for (i in 0 until ARKit.COUNT) output[i] = position[i].coerceIn(0f, 1f)
    }

    fun reset() {
        position.fill(0f)
        velocity.fill(0f)
        target.fill(0f)
        output.fill(0f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  TISSUE PROFILES
    // ═══════════════════════════════════════════════════════════════════════

    private fun initTissueProfiles() {

        // ── DEFAULT: стандартная мышечная ткань ───────────────────────────
        stiffness.fill(MUSC_K)
        damping.fill(MUSC_D)
        maxOvershoot.fill(MUSC_OS)

        // ── FAST: веки — ζ ≈ 1.0 (мгновенное смыкание) ──────────────────
        applyProfile(ARKit.GROUP_EYELIDS, FAST_K, FAST_D, FAST_OS)

        // ── FAST: зрачки — ещё быстрее век ───────────────────────────────
        applyProfile(ARKit.GROUP_PUPILS, FAST_K * 1.2f, FAST_D * 1.1f, 0.01f)

        // ── BONE: челюсть — тяжёлая кость ────────────────────────────────
        applyProfile(ARKit.GROUP_JAW, BONE_K, BONE_D, BONE_OS)

        // ── SPHINCTER: губное смыкание П/Б/М ─────────────────────────────
        applyProfile(ARKit.GROUP_LIP_SEAL, SPHX_K, SPHX_D, SPHX_OS)

        // ── MUSCLE: округление губ О/У/Ш ─────────────────────────────────
        applyProfile(ARKit.GROUP_LIP_ROUND, MUSC_K, MUSC_D, MUSC_OS)

        // ── MUSCLE: растяжение губ Е/И/улыбка ────────────────────────────
        applyProfile(ARKit.GROUP_LIP_STRETCH, MUSC_K * 1.1f, MUSC_D * 1.05f, MUSC_OS)

        // ── MUSCLE: вертикальные движения губ ────────────────────────────
        applyProfile(ARKit.GROUP_LIP_VERTICAL, MUSC_K, MUSC_D, MUSC_OS)

        // ── SLOW: брови — медленная эмоциональная ткань ──────────────────
        applyProfile(ARKit.GROUP_BROWS, SOFT_K, SOFT_D, SOFT_OS)

        // ── SOFT: щёки и нос — мясистая ткань ────────────────────────────
        applyProfile(ARKit.GROUP_CHEEKS_NOSE, SOFT_K * 0.9f, SOFT_D * 0.95f, SOFT_OS)

        // ── Frown: самые медленные (грусть накатывает плавно) ─────────────
        applyProfile(
            intArrayOf(ARKit.MouthFrownLeft, ARKit.MouthFrownRight),
            SLOW_K, SLOW_D, SLOW_OS,
        )

        // ── MouthRight / MouthLeft: асимметричный сдвиг рта ──────────────
        applyProfile(
            intArrayOf(ARKit.MouthRight, ARKit.MouthLeft),
            MUSC_K * 0.9f, MUSC_D, MUSC_OS,
        )

        // ── RollUpper / RollLower: губной рулик Ф/В ───────────────────────
        applyProfile(
            intArrayOf(ARKit.MouthRollLower, ARKit.MouthRollUpper),
            280f, 2f * sqrt(280f) * 0.88f, 0.05f,
        )

        // ── Shrug: плечо рта при сомнении ────────────────────────────────
        applyProfile(
            intArrayOf(ARKit.MouthShrugLower, ARKit.MouthShrugUpper),
            195f, 2f * sqrt(195f) * 0.84f, 0.05f,
        )

        // ── Dimples: ямочки от улыбки — мягкая ткань ─────────────────────
        applyProfile(
            intArrayOf(ARKit.MouthDimpleLeft, ARKit.MouthDimpleRight),
            175f, 2f * sqrt(175f) * 0.80f, 0.05f,
        )

        // ── CheekPuff: накачанные щёки — самая инертная ткань лица ───────
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