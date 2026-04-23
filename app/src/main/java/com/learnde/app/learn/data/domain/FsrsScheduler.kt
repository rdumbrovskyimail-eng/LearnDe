// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.2
// Путь: app/src/main/java/com/learnde/app/learn/domain/FsrsScheduler.kt
//
// ИЗМЕНЕНИЯ v3.2:
//   - MIN_INTERVAL_DAYS: 0.25 (6h) → 0.007 (~10 минут)
//   - Для AGAIN жёстко 10 минут, чтобы слабое слово всплыло в следующей сессии
//   - Для HARD максимум 6 часов (раньше могло уйти на дни)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.domain

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

enum class FsrsRating(val value: Int) {
    AGAIN(1), HARD(2), GOOD(3), EASY(4);

    companion object {
        fun fromQuality(quality: Int): FsrsRating = when (quality) {
            in 1..2 -> AGAIN
            in 3..4 -> HARD
            in 5..6 -> GOOD
            else -> EASY
        }
    }
}

data class FsrsState(
    val difficulty: Double,
    val stability: Double,
    val reps: Int,
    val lapses: Int,
    val lastReviewAt: Long,
) {
    fun retrievabilityAt(elapsedDays: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1.0 + elapsedDays / (9.0 * stability)).pow(-1.0)
    }

    companion object {
        fun initial() = FsrsState(
            difficulty = 5.0,
            stability = 0.0,
            reps = 0,
            lapses = 0,
            lastReviewAt = 0L,
        )
    }
}

@Singleton
class FsrsScheduler @Inject constructor() {

    private val initialStabilities = doubleArrayOf(0.40, 0.90, 2.30, 10.90)
    private val initialDifficulty = 5.0
    private val desiredRetention = 0.9

    private val w3 = 0.1
    private val w4 = 0.5
    private val w5 = 0.8
    private val w6 = 1.5
    private val w7 = 0.2
    private val w8 = 1.0
    private val w9 = 0.3
    private val w10 = 2.0

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
            prior.difficulty + w3 * (d0 - prior.difficulty)
        }.coerceIn(1.0, 10.0)

        // ── Stability ──
        val newS = if (isFirst) {
            initialStabilities[rating.value - 1]
        } else {
            val r = prior.retrievabilityAt(elapsedDays)
            when (rating) {
                FsrsRating.AGAIN -> {
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
        }.coerceIn(0.01, 36500.0)

        val newReps = prior.reps + 1
        val newLapses = prior.lapses + if (rating == FsrsRating.AGAIN) 1 else 0

        // ── Next review interval ──
        // v3.2: Жёсткие границы в зависимости от rating — чтобы активные сессии
        // позволяли слабым словам всплывать быстро.
        val intervalDays = when (rating) {
            FsrsRating.AGAIN -> {
                // Принудительно ~10 минут для полностью забытых — всплывёт в след. сессии
                AGAIN_INTERVAL_DAYS
            }
            FsrsRating.HARD -> {
                // Максимум 6 часов для "вспомнил с трудом"
                val calculated = 9.0 * newS * (1.0 / desiredRetention - 1.0)
                calculated.coerceIn(MIN_INTERVAL_DAYS, HARD_MAX_INTERVAL_DAYS)
            }
            else -> {
                val calculated = 9.0 * newS * (1.0 / desiredRetention - 1.0)
                calculated.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)
            }
        }
        val nextReviewAt = nowMs + (intervalDays * MS_PER_DAY).toLong()

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
     */
    fun masteryScore(state: FsrsState, nowMs: Long = System.currentTimeMillis()): Float {
        if (state.reps == 0) return 0f
        val elapsed = (nowMs - state.lastReviewAt) / MS_PER_DAY
        val r = state.retrievabilityAt(elapsed)
        val reliabilityBonus = (state.reps - state.lapses).coerceAtLeast(0).toDouble() /
            max(state.reps.toDouble(), 1.0)
        return ((r * 0.7 + reliabilityBonus * 0.3).coerceIn(0.0, 1.0)).toFloat()
    }

    companion object {
        const val MS_PER_DAY = 86_400_000.0
        const val MIN_INTERVAL_DAYS = 0.007           // v3.2: ~10 минут (было 6 часов)
        const val AGAIN_INTERVAL_DAYS = 0.007          // ~10 минут для провалов
        const val HARD_MAX_INTERVAL_DAYS = 0.25        // 6 часов max для hard
        const val MAX_INTERVAL_DAYS = 365.0
    }
}