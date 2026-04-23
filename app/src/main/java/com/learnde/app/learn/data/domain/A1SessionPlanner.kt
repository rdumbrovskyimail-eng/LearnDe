// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.2
// Путь: app/src/main/java/com/learnde/app/learn/domain/A1SessionPlanner.kt
//
// ИЗМЕНЕНИЯ v3.2:
//   - Увеличено количество review-лемм: 5 → 8 (больше повторов за сессию)
//   - Сначала берём due-леммы (FSRS), сортируем по retrievability (забытые — первыми)
//   - Добираем weakest если due-мало
//   - Добавлен метод pickReviewSessionLemmas() для A1ReviewSession
//   - Интервалы уменьшены для провалов (mastery < 0.3f) — 2h вместо 4h
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.domain

import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1GrammarDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.learn.data.db.GrammarRuleA1Entity
import com.learnde.app.learn.data.db.LemmaA1Entity
import com.learnde.app.util.AppLogger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Singleton
class A1SessionPlanner @Inject constructor(
    private val clusterDao: A1ClusterDao,
    private val lemmaDao: A1LemmaDao,
    private val grammarDao: A1GrammarDao,
    private val logger: AppLogger,
) {

    suspend fun pickNextCluster(): ClusterA1Entity? {
        val now = System.currentTimeMillis()
        val due = clusterDao.getDueForReview(now)
        if (due.isNotEmpty()) {
            val pick = due.first()
            logger.d("Planner: review cluster ${pick.id} (was due ${(now - (pick.nextReviewAt ?: now)) / 60000}m ago)")
            return pick
        }

        val next = clusterDao.getNextCluster(now)
        if (next != null) {
            logger.d("Planner: next unmastered cluster ${next.id}")
            return next
        }

        logger.d("Planner: no more clusters — A1 completed!")
        return null
    }

    /**
     * v3.2: Улучшенный выбор review-лемм.
     * Приоритет: due по FSRS → сортировка по retrievability → weakest.
     */
    suspend fun prepareSessionContext(cluster: ClusterA1Entity): SessionContext {
        val lemmaList = parseLemmas(cluster.lemmasJson)
        val clusterLemmas = lemmaDao.getByLemmas(lemmaList)

        val now = System.currentTimeMillis()
        val reviewLemmas = pickReviewLemmasForCluster(
            excludeLemmas = lemmaList.toSet(),
            limit = 8,
            now = now
        )

        val newRule = grammarDao.getNextRuleToIntroduce()

        logger.d("Planner: cluster=${cluster.id}, ${clusterLemmas.size} primary + ${reviewLemmas.size} review, grammar=${newRule?.id}")

        return SessionContext(
            cluster = cluster,
            primaryLemmas = clusterLemmas,
            reviewLemmas = reviewLemmas,
            grammarRuleToIntroduce = newRule,
        )
    }

    /**
     * v3.2: Для A1ReviewSession — только слабые/забытые леммы без привязки к кластеру.
     */
    suspend fun pickReviewSessionLemmas(limit: Int = 15): List<LemmaA1Entity> {
        val now = System.currentTimeMillis()
        return pickReviewLemmasForCluster(
            excludeLemmas = emptySet(),
            limit = limit,
            now = now
        )
    }

    /**
     * Приоритетная выборка лемм для повторения.
     * 1. Due по FSRS (уже "должны" повториться) — сортировка по retrievability (забытые первыми)
     * 2. Добор слабейшими, если due-список пуст/мал
     */
    private suspend fun pickReviewLemmasForCluster(
        excludeLemmas: Set<String>,
        limit: Int,
        now: Long,
    ): List<LemmaA1Entity> {
        // 1. Due-леммы по FSRS — самый высокий приоритет
        val dueForReview = lemmaDao.getDueForReview(now, limit = limit * 3)
            .filter { it.lemma !in excludeLemmas }
            .sortedBy { entity ->
                // Retrievability: чем ниже — тем выше приоритет повторения
                val state = entity.toFsrsState()
                val elapsed = if (state.lastReviewAt > 0)
                    (now - state.lastReviewAt) / 86_400_000.0
                else 0.0
                state.retrievabilityAt(elapsed)
            }
            .take(limit)

        if (dueForReview.size >= limit) {
            logger.d("Planner: review-mix = ${dueForReview.size} due-by-FSRS (no fill needed)")
            return dueForReview
        }

        // 2. Добор слабейшими — те, что давно видели + низкий productionScore
        val dueIds = dueForReview.map { it.lemma }.toSet()
        val weakFill = lemmaDao.getWeakestLemmas(limit = limit * 2)
            .filter { it.lemma !in excludeLemmas && it.lemma !in dueIds }
            .take(limit - dueForReview.size)

        val result = dueForReview + weakFill
        logger.d("Planner: review-mix = ${dueForReview.size} due + ${weakFill.size} weak = ${result.size} total")
        return result
    }

    suspend fun onSessionCompleted(
        cluster: ClusterA1Entity,
        overallQuality: Int,
        introducedRuleId: String?,
    ) {
        val masteryDelta = (overallQuality / 7f - 0.5f).coerceIn(-0.2f, 0.2f)
        val newMastery = (cluster.masteryScore + masteryDelta).coerceIn(0f, 1f)
        val nextReviewInterval = computeNextReviewInterval(newMastery)

        clusterDao.updateClusterProgress(
            id = cluster.id,
            masteryScore = newMastery,
            nextReview = System.currentTimeMillis() + nextReviewInterval,
        )
        logger.d("Planner: cluster ${cluster.id} mastery ${cluster.masteryScore} → $newMastery (next review in ${nextReviewInterval / 3_600_000}h)")

        if (newMastery >= 0.7f && cluster.masteryScore < 0.7f) {
            unlockDependentClusters(cluster.id)
        }

        if (introducedRuleId != null) {
            grammarDao.markIntroduced(introducedRuleId)
            logger.d("Planner: grammar rule $introducedRuleId marked as introduced")
        }
    }

    private suspend fun unlockDependentClusters(justMasteredId: String) {
        val all = clusterDao.getAllOrdered()
        val masteredIds = all.filter { it.masteryScore >= 0.7f }.map { it.id }.toSet() + justMasteredId

        for (c in all) {
            if (c.isUnlocked) continue
            val prereqs = parsePrerequisites(c.prerequisitesJson)
            if (prereqs.all { it in masteredIds }) {
                clusterDao.markUnlocked(c.id)
                logger.d("Planner: UNLOCKED ${c.id} (all prereqs mastered)")
            }
        }
    }

    /**
     * v3.2: Более агрессивные повторы для провалов.
     * < 0.3 mastery → 2 часа (было 4)
     * < 0.5 → 12 часов (было 1 день)
     */
    private fun computeNextReviewInterval(mastery: Float): Long = when {
        mastery < 0.3f -> 2.hours.inWholeMilliseconds
        mastery < 0.5f -> 12.hours.inWholeMilliseconds
        mastery < 0.7f -> 3.days.inWholeMilliseconds
        mastery < 0.9f -> 7.days.inWholeMilliseconds
        else           -> 21.days.inWholeMilliseconds
    }

    // ─── Helpers ───
    private fun parseLemmas(jsonStr: String): List<String> = try {
        Json.decodeFromString<List<String>>(jsonStr)
    } catch (_: Exception) { emptyList() }

    private fun parsePrerequisites(jsonStr: String): List<String> = parseLemmas(jsonStr)
}

data class SessionContext(
    val cluster: ClusterA1Entity,
    val primaryLemmas: List<LemmaA1Entity>,
    val reviewLemmas: List<LemmaA1Entity>,
    val grammarRuleToIntroduce: GrammarRuleA1Entity?,
)