package com.codeextractor.app.domain.avatar

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * IdleAnimator v4 — Биологически корректная idle-анимация
 *
 * РОЛЬ В ПАЙПЛАЙНЕ:
 *   IdleAnimator генерирует АДДИТИВНЫЙ слой поверх визем речи.
 *   AvatarAnimatorImpl объединяет их через max():
 *     physics.setTarget(i, max(speechViseme[i], idle[i]))
 *
 *   Это значит idle НИКОГДА не мешает речи —
 *   он работает только там, где речь не задействует blendshape.
 *
 * КОМПОНЕНТЫ:
 *
 *   1. BLINK — физиологически корректное моргание
 *      Интервал:  2–6 сек базово, ×1.6 при активной речи (люди реже моргают)
 *      Профиль:   close 60ms / hold 55ms / open 85ms (asymmetric — быстрее закрываем)
 *      Источник:  данные Evinger et al. 1991, Duke-Elder 1965
 *
 *   2. MICRO-SACCADES — микродвижения зрачков
 *      Человеческий глаз не стоит абсолютно неподвижно: «дрожит» (тремор),
 *      совершает микро-дрейф и корректирующие микро-саккады.
 *      Здесь моделируются только микро-саккады (смещения 0.5–2°):
 *      случайная цель → плавное движение → новая цель через 0.5–2 сек.
 *
 *   3. BREATHING — дыхательный ритм
 *      JawOpen ±0.012 с частотой ~0.8 Гц (~4.8 сек цикл в покое).
 *      Только нижняя челюсть — реалистично, без участия рта.
 *      HeadMotionEngine добавляет pitch-качку от того же дыхания.
 *
 *   4. MICRO-BROW — случайные микро-движения бровей
 *      Каждые 5–15 сек одна бровь слегка дёргается (BrowInnerUp).
 *      Это «эмоциональный шум» — непроизвольные микровыражения.
 *      Амплитуда 0–0.06 — почти незаметно сознательно, но считывается подсознательно.
 *
 *   5. NOSTRIL FLARE — лёгкое раздувание ноздрей при дыхании
 *      NoseSneer ×0.015 синхронно с дыхательным циклом.
 *      Настолько мало, что не воспринимается как «злость» — только как жизнь.
 *
 * THREAD-SAFETY:
 *   Single writer (animator coroutine). Все состояния — примитивы, без аллокаций.
 *
 * OUTPUT:
 *   FloatArray(ARKit.COUNT) — переиспользуемый внутренний буфер.
 *   Вызывающий код НЕ должен держать ссылку между кадрами.
 */
class IdleAnimator {

    companion object {
        // ── Blink timings (секунды) ───────────────────────────────────────
        private const val BLINK_CLOSE_DURATION  = 0.060f   // быстрое закрытие
        private const val BLINK_HOLD_DURATION   = 0.055f   // полностью закрыты
        private const val BLINK_OPEN_DURATION   = 0.085f   // медленное открытие
        private const val BLINK_TOTAL           = BLINK_CLOSE_DURATION +
                                                  BLINK_HOLD_DURATION +
                                                  BLINK_OPEN_DURATION  // ≈ 0.20 сек

        private const val BLINK_INTERVAL_MIN    = 2.0f
        private const val BLINK_INTERVAL_MAX    = 6.0f
        private const val BLINK_SPEAKING_MULT   = 1.6f     // реже при речи

        // ── Micro-saccade ─────────────────────────────────────────────────
        private const val SACCADE_INTERVAL_MIN  = 0.5f
        private const val SACCADE_INTERVAL_MAX  = 2.0f
        private const val SACCADE_MAGNITUDE     = 0.08f    // [0..1] в пространстве blendshape
        private const val SACCADE_SMOOTH        = 8.0f     // скорость следования

        // ── Breathing ─────────────────────────────────────────────────────
        private const val BREATH_FREQ           = 0.80f    // Гц (~4.8 сек цикл)
        private const val BREATH_JAW_AMP        = 0.012f   // JawOpen при вдохе
        private const val BREATH_NOSTRIL_AMP    = 0.014f   // NoseSneer при вдохе

        // ── Micro-brow ────────────────────────────────────────────────────
        private const val BROW_EVENT_MIN        = 5.0f     // сек до следующего события
        private const val BROW_EVENT_MAX        = 15.0f
        private const val BROW_DURATION         = 0.35f    // длительность микродвижения
        private const val BROW_MAX_AMP          = 0.055f   // максимальная амплитуда

        // ── Snap threshold (против микро-дрожания saccade) ────────────────
        private const val SACCADE_SNAP          = 0.003f
    }

    // ── Output buffer (переиспользуется, zero-alloc) ─────────────────────
    private val weights = FloatArray(ARKit.COUNT)

    // ── BLINK state ───────────────────────────────────────────────────────
    private var blinkTimer   = randomBlinkInterval(isSpeaking = false)
    private var blinkPhase   = -1f    // -1 = не моргаем; ≥ 0 = идёт моргание

    // ── MICRO-SACCADE state ───────────────────────────────────────────────
    private var saccadeTimer = randomSaccadeInterval()
    private var saccadeTargetX = 0f   // [-1..+1]: отрицательное = влево
    private var saccadeTargetY = 0f   // [-1..+1]: отрицательное = вниз
    private var saccadeCurrentX = 0f  // текущее сглаженное положение
    private var saccadeCurrentY = 0f

    // ── BREATHING state ───────────────────────────────────────────────────
    private var breathPhase = Random.nextFloat() * (2f * Math.PI.toFloat())

    // ── MICRO-BROW state ──────────────────────────────────────────────────
    private var browEventTimer   = randomBrowInterval()
    private var browEventPhase   = -1f   // -1 = нет события
    private var browEventSide    = 0     // 0 = оба, 1 = левая, 2 = правая
    private var browEventAmp     = 0f

    // ═══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Обновляет idle-веса.
     *
     * @param dtMs       delta time в мс
     * @param isSpeaking AI говорит (влияет на частоту моргания)
     *
     * @return FloatArray(ARKit.COUNT) — аддитивные idle-веса.
     *         Значения [0..1]. Обнуляются в начале каждого кадра.
     */
    fun update(dtMs: Long, isSpeaking: Boolean): FloatArray {
        val dt = dtMs.coerceIn(1, 32) / 1000f
        weights.fill(0f)

        updateBlink(dt, isSpeaking)
        updateMicroSaccade(dt)
        updateBreathing(dt)
        updateMicroBrow(dt)

        return weights
    }

    /** Сброс при смене сессии / перезапуске аниматора. */
    fun reset() {
        weights.fill(0f)
        blinkTimer   = randomBlinkInterval(isSpeaking = false)
        blinkPhase   = -1f
        saccadeTimer = randomSaccadeInterval()
        saccadeTargetX  = 0f; saccadeTargetY  = 0f
        saccadeCurrentX = 0f; saccadeCurrentY = 0f
        breathPhase  = Random.nextFloat() * (2f * Math.PI.toFloat())
        browEventTimer = randomBrowInterval()
        browEventPhase = -1f
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BLINK
    // ═══════════════════════════════════════════════════════════════════════

    private fun updateBlink(dt: Float, isSpeaking: Boolean) {
        if (blinkPhase >= 0f) {
            // Активное моргание
            blinkPhase += dt
            val blinkVal = computeBlinkValue(blinkPhase)

            weights[ARKit.EyeBlinkLeft]  = blinkVal
            weights[ARKit.EyeBlinkRight] = blinkVal

            // Лёгкое прищуривание при закрытом веке
            if (blinkVal > 0.3f) {
                val squint = (blinkVal - 0.3f) * 0.25f
                weights[ARKit.EyeSquintLeft]  = squint
                weights[ARKit.EyeSquintRight] = squint
            }

            if (blinkPhase >= BLINK_TOTAL) {
                blinkPhase = -1f
                blinkTimer = randomBlinkInterval(isSpeaking)
            }
        } else {
            // Ожидание следующего моргания
            blinkTimer -= dt
            if (blinkTimer <= 0f) {
                blinkPhase = 0f
            }
        }
    }

    /**
     * Профиль моргания:
     *   [0 .. CLOSE_END)  — экспоненциальное закрытие (быстрое)
     *   [CLOSE_END .. HOLD_END) — полное закрытие
     *   [HOLD_END .. TOTAL) — линейное открытие (медленнее)
     *
     * Экспоненциальное закрытие: replicates orbicularis oculi burst.
     */
    private fun computeBlinkValue(phase: Float): Float {
        val closeEnd = BLINK_CLOSE_DURATION
        val holdEnd  = closeEnd + BLINK_HOLD_DURATION

        return when {
            phase < closeEnd -> {
                // Быстрое закрытие: easeIn (квадратичное)
                val t = phase / closeEnd
                t * t
            }
            phase < holdEnd -> 1f
            phase < BLINK_TOTAL -> {
                // Медленное открытие: easeOut
                val t = (phase - holdEnd) / BLINK_OPEN_DURATION
                1f - t
            }
            else -> 0f
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MICRO-SACCADE
    // ═══════════════════════════════════════════════════════════════════════

    private fun updateMicroSaccade(dt: Float) {
        // Таймер следующей саккады
        saccadeTimer -= dt
        if (saccadeTimer <= 0f) {
            // Выбираем новую случайную цель
            saccadeTargetX = (Random.nextFloat() - 0.5f) * 2f * SACCADE_MAGNITUDE
            saccadeTargetY = (Random.nextFloat() - 0.5f) * 2f * SACCADE_MAGNITUDE
            saccadeTimer   = randomSaccadeInterval()
        }

        // Сглаживание к цели
        val speed = SACCADE_SMOOTH * dt
        saccadeCurrentX += (saccadeTargetX - saccadeCurrentX) * speed
        saccadeCurrentY += (saccadeTargetY - saccadeCurrentY) * speed

        // Snap при сходимости (убирает вечные микро-движения)
        if (abs(saccadeCurrentX - saccadeTargetX) < SACCADE_SNAP) saccadeCurrentX = saccadeTargetX
        if (abs(saccadeCurrentY - saccadeTargetY) < SACCADE_SNAP) saccadeCurrentY = saccadeTargetY

        // Применяем к EyeLook blendshapes
        applyEyeLook(saccadeCurrentX, saccadeCurrentY)
    }

    /**
     * Конвертирует нормализованные [-1..+1] координаты в EyeLook blendshapes.
     *
     * X: отрицательное → In (смотрит к носу), положительное → Out
     * Y: отрицательное → Down, положительное → Up
     *
     * Левый и правый глаз симметричны (направление одинаковое,
     * но In/Out инвертированы по физиологии).
     */
    private fun applyEyeLook(x: Float, y: Float) {
        val absX = abs(x).coerceIn(0f, 1f)
        val absY = abs(y).coerceIn(0f, 1f)

        if (x > 0f) {
            // Смотрим вправо: Left=Out, Right=In
            weights[ARKit.EyeLookOutLeft]  = absX
            weights[ARKit.EyeLookInRight]  = absX
        } else {
            // Смотрим влево: Left=In, Right=Out
            weights[ARKit.EyeLookInLeft]   = absX
            weights[ARKit.EyeLookOutRight] = absX
        }

        if (y > 0f) {
            weights[ARKit.EyeLookUpLeft]   = absY
            weights[ARKit.EyeLookUpRight]  = absY
        } else {
            weights[ARKit.EyeLookDownLeft]  = absY
            weights[ARKit.EyeLookDownRight] = absY
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BREATHING
    // ═══════════════════════════════════════════════════════════════════════

    private fun updateBreathing(dt: Float) {
        breathPhase += dt * BREATH_FREQ * (2f * Math.PI.toFloat())

        // sin: −1 (выдох) .. +1 (вдох)
        // Нормализуем к [0..1] для положительных blendshape
        val breathNorm = (sin(breathPhase) * 0.5f + 0.5f)

        // Нижняя челюсть слегка открывается при вдохе
        weights[ARKit.JawOpen] = breathNorm * BREATH_JAW_AMP

        // Ноздри слегка раздуваются при вдохе (очень тонко)
        val nostrilFlare = breathNorm * BREATH_NOSTRIL_AMP
        weights[ARKit.NoseSneerLeft]  = nostrilFlare
        weights[ARKit.NoseSneerRight] = nostrilFlare
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MICRO-BROW
    // ═══════════════════════════════════════════════════════════════════════

    private fun updateMicroBrow(dt: Float) {
        if (browEventPhase >= 0f) {
            // Идёт событие: треугольный профиль (нарастание → спад)
            browEventPhase += dt
            val t = browEventPhase / BROW_DURATION
            val browVal = when {
                t < 0.4f -> browEventAmp * (t / 0.4f)           // нарастание
                t < 0.7f -> browEventAmp                         // пик
                t < 1.0f -> browEventAmp * ((1f - t) / 0.3f)   // спад
                else     -> { browEventPhase = -1f; 0f }
            }

            when (browEventSide) {
                1 -> {  // только левая бровь
                    weights[ARKit.BrowInnerUp]    = browVal * 0.7f
                    weights[ARKit.BrowOuterUpLeft] = browVal * 0.4f
                }
                2 -> {  // только правая бровь
                    weights[ARKit.BrowInnerUp]     = browVal * 0.7f
                    weights[ARKit.BrowOuterUpRight] = browVal * 0.4f
                }
                else -> {  // обе брови (удивление)
                    weights[ARKit.BrowInnerUp]      = browVal
                    weights[ARKit.BrowOuterUpLeft]  = browVal * 0.5f
                    weights[ARKit.BrowOuterUpRight] = browVal * 0.5f
                }
            }
        } else {
            // Ожидание следующего события
            browEventTimer -= dt
            if (browEventTimer <= 0f) {
                // Запускаем новое событие
                browEventPhase = 0f
                browEventAmp   = Random.nextFloat() * BROW_MAX_AMP
                browEventSide  = when (Random.nextInt(4)) {
                    0    -> 1     // только левая (25%)
                    1    -> 2     // только правая (25%)
                    else -> 0     // обе (50%)
                }
                browEventTimer = randomBrowInterval()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun randomBlinkInterval(isSpeaking: Boolean): Float {
        val base = BLINK_INTERVAL_MIN +
                Random.nextFloat() * (BLINK_INTERVAL_MAX - BLINK_INTERVAL_MIN)
        return if (isSpeaking) base * BLINK_SPEAKING_MULT else base
    }

    private fun randomSaccadeInterval(): Float =
        SACCADE_INTERVAL_MIN +
                Random.nextFloat() * (SACCADE_INTERVAL_MAX - SACCADE_INTERVAL_MIN)

    private fun randomBrowInterval(): Float =
        BROW_EVENT_MIN + Random.nextFloat() * (BROW_EVENT_MAX - BROW_EVENT_MIN)
}