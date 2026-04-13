package com.codeextractor.app.domain.avatar

import kotlin.math.abs
import kotlin.math.min

/**
 * CoArticulator v4 — Velocity-Aware Temporal Blending
 *
 * ЗАЧЕМ СУЩЕСТВУЕТ CoArticulator (если есть FacePhysicsEngine):
 *
 *   FacePhysicsEngine сглаживает ИНДИВИДУАЛЬНЫЕ blendshapes во времени.
 *   CoArticulator решает другую задачу: ВРЕМЕННОЕ ПЕРЕКРЫТИЕ ФОНЕМ.
 *
 *   Пример: фраза «МА»
 *   - VisemeMapper выдаёт: кадр 1 → M (губы сжаты), кадр 2 → A (рот открыт)
 *   - Без CoArticulator: резкий переход M→A
 *   - С CoArticulator: в кадр 2 добавляется «хвост» от M (carry),
 *     и небольшой look-ahead от будущего A (lead)
 *   - Результат: губы плавно «разлепляются» с физиологически верным timing'ом
 *
 *   FacePhysicsEngine не может это сделать — он не знает о предыдущих ФОНЕМАХ,
 *   только о предыдущих ЗНАЧЕНИЯХ конкретного blendshape.
 *
 * АЛГОРИТМ:
 *
 *   output[i] = current[i] * mainWeight
 *             + prev[i]    * carry        ← инерция предыдущей фонемы
 *             + trend[i]   * lead         ← предчувствие следующей фонемы
 *
 *   где:
 *     carry = regionCarry[i] * velFactor  ← per-region + velocity gating
 *     lead  = 0.035 * velFactor           ← look-ahead подавлен при взрывных
 *     trend = 2 * prev - prev2            ← линейная экстраполяция
 *
 * VELOCITY GATING:
 *
 *   При высокой скорости изменения blendshape (взрывные П, Б, К):
 *     velFactor → 0.05 (минимальный carry)
 *   При медленных переходах (гласные, брови):
 *     velFactor → 1.0 (полный carry)
 *
 *   Это решает проблему «размытых взрывных» — П не «размазывается» в А,
 *   а А не «обрывает» П раньше времени.
 *
 * VELOCITY SMOOTHING:
 *
 *   Скорость сглаживается через exponential moving average (α = 0.4)
 *   чтобы один шумный кадр не сбивал всю кинематику.
 *
 * PER-REGION CARRY WEIGHTS:
 *
 *   Каждая анатомическая группа имеет свой базовый carry.
 *   Группы с высоким carry: брови (0.33), щёки (0.28), челюсть (0.30).
 *   Группы с низким carry:  губное смыкание (0.06), веки (0.03).
 *
 * HISTORY SIZE = 4:
 *   Достаточно для двух предыдущих фонем + trend.
 *   Больший размер даёт артефакты (старые фонемы «просачиваются» в речь).
 *
 * ZERO-ALLOCATION:
 *   Все буферы pre-allocated. history — Array(4) { FloatArray(COUNT) }.
 *   Запись через ringPos — без arraycopy истории при каждом кадре.
 */
class CoArticulator(private val historySize: Int = 4) {

    companion object {
        // ── Default carry ────────────────────────────────────────────────
        private const val DEFAULT_CARRY  = 0.17f

        // ── Lead (look-ahead) ────────────────────────────────────────────
        private const val LEAD_BASE      = 0.035f

        // ── Velocity gating ──────────────────────────────────────────────
        private const val VEL_SENSITIVITY = 3.2f    // чувствительность к скорости
        private const val VEL_MIN_FACTOR  = 0.05f   // минимальный carry при взрывных
        private const val VEL_SMOOTH      = 0.40f   // EMA alpha для velocity

        // ── Per-region carry (базовые значения) ──────────────────────────
        // Челюсть: высокая инерция
        private const val CARRY_JAW        = 0.30f
        // Губное смыкание: мгновенная реакция (взрывные не размазываются)
        private const val CARRY_LIP_SEAL   = 0.06f
        // Округление/растяжение губ
        private const val CARRY_LIP_ROUND  = 0.20f
        private const val CARRY_LIP_STRCH  = 0.15f
        // Вертикальные движения
        private const val CARRY_LIP_VERT   = 0.14f
        // Roll/Shrug
        private const val CARRY_LIP_ROLL   = 0.18f
        // Frown (медленная эмоциональная ткань)
        private const val CARRY_FROWN      = 0.28f
        // Dimples
        private const val CARRY_DIMPLE     = 0.14f
        // Брови: медленные, эмоции накатывают и уходят плавно
        private const val CARRY_BROWS      = 0.33f
        // Веки/зрачки: мгновенные
        private const val CARRY_EYES       = 0.03f
        // Щёки/нос: мясистая ткань
        private const val CARRY_CHEEKS     = 0.28f
        private const val CARRY_NOSE       = 0.22f
    }

    // ── Ring buffer истории ───────────────────────────────────────────────
    private val history  = Array(historySize) { FloatArray(ARKit.COUNT) }
    private var ringPos  = 0
    private var filled   = 0

    // ── Рабочие буферы (pre-allocated, zero-alloc в hot path) ────────────
    private val output   = FloatArray(ARKit.COUNT)
    private val velocity = FloatArray(ARKit.COUNT)   // сглаженная скорость изменения

    // ── Per-region carry weights ──────────────────────────────────────────
    private val regionCarry = FloatArray(ARKit.COUNT)

    init { initRegionWeights() }

    // ═══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Обрабатывает кадр визем.
     *
     * @param rawWeights  выход VisemeMapper (ARKit.COUNT значений)
     * @return FloatArray(ARKit.COUNT) — ко-артикулированные веса.
     *         Ссылка действительна до следующего вызова process().
     */
    fun process(rawWeights: FloatArray): FloatArray {
        val curIdx  = ringPos % historySize
        val prevIdx = (ringPos - 1 + historySize) % historySize
        val prev2Idx = (ringPos - 2 + historySize) % historySize

        // ── Обновляем сглаженную скорость (EMA) ──────────────────────────
        if (filled > 0) {
            val prev = history[prevIdx]
            for (i in 0 until ARKit.COUNT) {
                val instantVel = abs(rawWeights[i] - prev[i])
                // Exponential moving average: сглаживает шумные кадры
                velocity[i] = velocity[i] * (1f - VEL_SMOOTH) + instantVel * VEL_SMOOTH
            }
        }

        // ── Записываем в историю ──────────────────────────────────────────
        rawWeights.copyInto(history[curIdx], endIndex = ARKit.COUNT)
        ringPos++
        filled = min(filled + 1, historySize)

        // ── Недостаточно истории → пропускаем без изменений ──────────────
        if (filled < 2) {
            rawWeights.copyInto(output, endIndex = ARKit.COUNT)
            return output
        }

        val prev  = history[prevIdx]
        val prev2 = if (filled >= 3) history[prev2Idx] else prev

        // ── CoArticulation blending ───────────────────────────────────────
        for (i in 0 until ARKit.COUNT) {
            // Velocity factor: высокая скорость → меньше carry
            val velFactor = (1f - velocity[i] * VEL_SENSITIVITY)
                .coerceIn(VEL_MIN_FACTOR, 1f)

            val carry = regionCarry[i] * velFactor
            val lead  = LEAD_BASE * velFactor

            // Линейная экстраполяция тренда (look-ahead)
            val trend = (2f * prev[i] - prev2[i]).coerceIn(0f, 1f)

            val mainWeight = (1f - carry - lead).coerceAtLeast(0f)

            output[i] = (rawWeights[i] * mainWeight +
                         prev[i]       * carry       +
                         trend         * lead)
                .coerceIn(0f, 1f)
        }

        return output
    }

    /** Сброс при смене сессии. */
    fun reset() {
        for (buf in history) buf.fill(0f)
        ringPos = 0; filled = 0
        output.fill(0f); velocity.fill(0f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  REGION CARRY WEIGHTS
    // ═══════════════════════════════════════════════════════════════════════

    private fun initRegionWeights() {
        // Default
        regionCarry.fill(DEFAULT_CARRY)

        // ── Челюсть ───────────────────────────────────────────────────────
        for (i in ARKit.GROUP_JAW) regionCarry[i] = CARRY_JAW

        // ── Губное смыкание (взрывные П/Б/М) ─────────────────────────────
        for (i in ARKit.GROUP_LIP_SEAL) regionCarry[i] = CARRY_LIP_SEAL

        // ── Округление губ (О/У/Ш) ────────────────────────────────────────
        for (i in ARKit.GROUP_LIP_ROUND) regionCarry[i] = CARRY_LIP_ROUND

        // ── Растяжение губ (Е/И/улыбка) ───────────────────────────────────
        for (i in ARKit.GROUP_LIP_STRETCH) regionCarry[i] = CARRY_LIP_STRCH

        // ── Вертикальные движения губ ─────────────────────────────────────
        for (i in ARKit.GROUP_LIP_VERTICAL) regionCarry[i] = CARRY_LIP_VERT

        // ── Roll / Shrug ──────────────────────────────────────────────────
        regionCarry[ARKit.MouthRollLower]  = CARRY_LIP_ROLL
        regionCarry[ARKit.MouthRollUpper]  = CARRY_LIP_ROLL
        regionCarry[ARKit.MouthShrugLower] = CARRY_LIP_ROLL
        regionCarry[ARKit.MouthShrugUpper] = CARRY_LIP_ROLL

        // ── Frown ─────────────────────────────────────────────────────────
        regionCarry[ARKit.MouthFrownLeft]  = CARRY_FROWN
        regionCarry[ARKit.MouthFrownRight] = CARRY_FROWN

        // ── Dimples ───────────────────────────────────────────────────────
        regionCarry[ARKit.MouthDimpleLeft]  = CARRY_DIMPLE
        regionCarry[ARKit.MouthDimpleRight] = CARRY_DIMPLE

        // ── Брови ─────────────────────────────────────────────────────────
        for (i in ARKit.GROUP_BROWS) regionCarry[i] = CARRY_BROWS

        // ── Веки и зрачки ─────────────────────────────────────────────────
        for (i in ARKit.GROUP_EYELIDS) regionCarry[i] = CARRY_EYES
        for (i in ARKit.GROUP_PUPILS)  regionCarry[i] = CARRY_EYES

        // ── Щёки ──────────────────────────────────────────────────────────
        regionCarry[ARKit.CheekPuff]        = CARRY_CHEEKS
        regionCarry[ARKit.CheekSquintLeft]  = CARRY_CHEEKS
        regionCarry[ARKit.CheekSquintRight] = CARRY_CHEEKS

        // ── Нос ───────────────────────────────────────────────────────────
        regionCarry[ARKit.NoseSneerLeft]  = CARRY_NOSE
        regionCarry[ARKit.NoseSneerRight] = CARRY_NOSE

        // ── MouthRight / MouthLeft (асимметричный сдвиг рта) ──────────────
        regionCarry[ARKit.MouthRight] = DEFAULT_CARRY
        regionCarry[ARKit.MouthLeft]  = DEFAULT_CARRY
    }
}