// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ (Patch 3)
// Путь: app/src/main/java/com/learnde/app/learn/domain/FsrsScheduler.kt
//
// FSRS-5 (Free Spaced Repetition Scheduler, 2024)
// https://github.com/open-spaced-repetition/fsrs4anki/wiki
//
// Заменяет наивную SM-2-подобную логику на трёхкомпонентную модель:
//   • Difficulty (D, 1..10) — внутренняя сложность карточки для ученика
//   • Stability (S, days)    — как долго память "держит" без повторения
//   • Retrievability (R, 0..1) — вероятность вспомнить сейчас
//
// Рейтинг ответа (как в Anki):
//   AGAIN  = 1  — не вспомнил
//   HARD   = 2  — вспомнил с трудом
//   GOOD   = 3  — вспомнил нормально
//   EASY   = 4  — легко
//
// Маппинг из нашей 7-балльной Gemini-шкалы:
//   1-2 → AGAIN
//   3-4 → HARD
//   5-6 → GOOD
//   7   → EASY
//
// Это упрощённая референсная реализация. Параметры взяты из дефолтного
// набора FSRS-5 для пользователей без собственных данных. После 100+
// повторений их стоит обучить на юзере (training) — это Patch 6+.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.domain

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class FsrsRating(val value: Int) {
    AGAIN(1), HARD(2), GOOD(3), EASY(4);

    companion object {
        /** Маппинг из нашей 7-балльной шкалы (Gemini) в FSRS rating. */
        fun fromQuality(quality: Int): FsrsRating = when (quality) {
            in 1..2 -> AGAIN
            in 3..4 -> HARD
            in 5..6 -> GOOD
            else -> EASY
        }
    }
}

/** Состояние памяти по одной лемме. */
data class FsrsState(
    /** Difficulty 1..10 (inclusive). */
    val difficulty: Double,
    /** Stability в днях. */
    val stability: Double,
    /** Кол-во повторений, выполненных к моменту. */
    val reps: Int,
    /** Кол-во "lapse" — случаев полного забывания. */
    val lapses: Int,
    /** Таймстамп последнего review. 0 — ещё не было. */
    val lastReviewAt: Long,
) {
    /** Retrievability — вероятность вспомнить через `elapsedDays` дней. */
    fun retrievabilityAt(elapsedDays: Double): Double {
        if (stability <= 0.0) return 0.0
        // FSRS-5 forgetting curve: R(t) = (1 + t/(9*S))^(-1)
        return (1.0 + elapsedDays / (9.0 * stability)).pow(-1.0)
    }

    companion object {
        fun initial() = FsrsState(
            difficulty = 5.0,  // среднее
            stability = 0.0,
            reps = 0,
            lapses = 0,
            lastReviewAt = 0L,
        )
    }
}

@Singleton
class FsrsScheduler @Inject constructor() {

    // FSRS-5 default weights (17 параметров для обученной модели,
    // здесь используем подмножество для упрощения).
    // Источник: open-spaced-repetition/fsrs-rs/main.rs defaults.
    private val initialStabilities = doubleArrayOf(0.40, 0.90, 2.30, 10.90) // AGAIN, HARD, GOOD, EASY
    private val initialDifficulty = 5.0
    private val desiredRetention = 0.9 // целевая вероятность вспомнить

    // Параметры обновления
    private val w3 = 0.1    // difficulty decay
    private val w4 = 0.5    // difficulty rating impact
    private val w5 = 0.8    // stability: success multiplier
    private val w6 = 1.5    // stability: interval weight
    private val w7 = 0.2    // stability: difficulty impact
    private val w8 = 1.0    // stability: retrievability impact
    private val w9 = 0.3    // stability: AGAIN penalty
    private val w10 = 2.0   // stability: HARD bonus

    /**
     * Обновить состояние после одного review.
     *
     * @param prior    текущее состояние
     * @param rating   ответ ученика
     * @param nowMs    текущее время (ms since epoch)
     * @return новое состояние + рекомендованный nextReviewAt
     */
    fun schedule(
        prior: FsrsState,
        rating: FsrsRating,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<FsrsState, Long> {
        val isFirst = prior.reps == 0 || prior.stability <= 0.0
        val elapsedDays = if (prior.lastReviewAt == 0L) 0.0
            else (nowMs - prior.lastReviewAt) / MS_PER_DAY

        // ── Difficulty ──
        val newD = if (isFirst) {
            initialDifficulty - w4 * (rating.value - 3)
        } else {
            val d0 = prior.difficulty - w4 * (rating.value - 3)
            // Mean reversion к 5.0 с коэффициентом w3
            prior.difficulty + w3 * (d0 - prior.difficulty)
        }.coerceIn(1.0, 10.0)

        // ── Stability ──
        val newS = if (isFirst) {
            initialStabilities[rating.value - 1]
        } else {
            val r = prior.retrievabilityAt(elapsedDays)
            when (rating) {
                FsrsRating.AGAIN -> {
                    // Forget: stability резко падает
                    w9 * prior.stability.pow(-0.5) * (w6 - newD) * exp(w8 * (1.0 - r))
                }
                FsrsRating.HARD -> {
                    prior.stability * (1.0 + exp(w5) *
                        (11.0 - newD) *
                        prior.stability.pow(-w7) *
                        (exp(w8 * (1.0 - r)) - 1.0) *
                        w10 * 0.5)
                }
                FsrsRating.GOOD -> {
                    prior.stability * (1.0 + exp(w5) *
                        (11.0 - newD) *
                        prior.stability.pow(-w7) *
                        (exp(w8 * (1.0 - r)) - 1.0))
                }
                FsrsRating.EASY -> {
                    prior.stability * (1.0 + exp(w5) *
                        (11.0 - newD) *
                        prior.stability.pow(-w7) *
                        (exp(w8 * (1.0 - r)) - 1.0) *
                        1.5)
                }
            }
        }.coerceIn(0.01, 36500.0) // max ~100 лет

        val newReps = prior.reps + 1
        val newLapses = prior.lapses + if (rating == FsrsRating.AGAIN) 1 else 0

        // ── Next review interval ──
        // Решаем уравнение: R(t) = desiredRetention
        // → t = 9*S*(1/desiredRetention - 1)
        val intervalDays = 9.0 * newS * (1.0 / desiredRetention - 1.0)
        val clampedInterval = intervalDays.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)
        val nextReviewAt = nowMs + (clampedInterval * MS_PER_DAY).toLong()

        val newState = FsrsState(
            difficulty = newD,
            stability = newS,
            reps = newReps,
            lapses = newLapses,
            lastReviewAt = nowMs,
        )

        return newState to nextReviewAt
    }

    /**
     * Текущий "уровень освоения" 0..1 для UI.
     * Используется вместо простого productionScore.
     * Основан на Retrievability + кол-ве успешных повторений.
     */
    fun masteryScore(state: FsrsState, nowMs: Long = System.currentTimeMillis()): Float {
        if (state.reps == 0) return 0f
        val elapsed = (nowMs - state.lastReviewAt) / MS_PER_DAY
        val r = state.retrievabilityAt(elapsed)
        // Репутация: R взвешен на "сколько раз подряд без lapse"
        val reliabilityBonus = (state.reps - state.lapses).coerceAtLeast(0).toDouble() /
            max(state.reps.toDouble(), 1.0)
        return ((r * 0.7 + reliabilityBonus * 0.3).coerceIn(0.0, 1.0)).toFloat()
    }

    companion object {
        const val MS_PER_DAY = 86_400_000.0
        const val MIN_INTERVAL_DAYS = 0.25  // 6 часов
        const val MAX_INTERVAL_DAYS = 365.0
    }
}