// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/domain/A1SessionPlanner.kt
//
// Планировщик учебных сессий. Два главных вопроса:
//   1) Какой кластер учить дальше?
//   2) Когда повторить то, что уже учил?
//
// Логика:
//   - Если есть кластеры "due for review" (SRS) → повторяем их
//   - Иначе берём следующий непройденный из unlocked
//   - После сессии: разблокируем новые кластеры, у которых все
//     prerequisites теперь пройдены.
//
// SRS-интервалы (упрощённый алгоритм Ebbinghaus / Anki):
//   mastery 0.0-0.3 → повтор через 4 часа
//   mastery 0.3-0.5 → через 1 день
//   mastery 0.5-0.7 → через 3 дня
//   mastery 0.7-0.9 → через 7 дней
//   mastery >= 0.9  → через 21 день
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.domain

import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1GrammarDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.learn.data.db.GrammarRuleA1Entity
import com.learnde.app.learn.data.db.LemmaA1Entity
import com.learnde.app.util.AppLogger
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@Singleton
class A1SessionPlanner @Inject constructor(
    private val clusterDao: A1ClusterDao,
    private val lemmaDao: A1LemmaDao,
    private val grammarDao: A1GrammarDao,
    private val logger: AppLogger,
) {

    /**
     * Выбирает следующий кластер для сессии.
     * Приоритет:
     *   1. Due for review (SRS истёк)
     *   2. Unlocked & never completed
     *   3. null (всё пройдено — A1 завершён)
     */
    suspend fun pickNextCluster(): ClusterA1Entity? {
        // Сначала проверяем SRS-очередь
        val now = System.currentTimeMillis()
        val due = clusterDao.getDueForReview(now)
        if (due.isNotEmpty()) {
            val pick = due.first()
            logger.d("Planner: review cluster ${pick.id} (was due ${(now - (pick.nextReviewAt ?: now)) / 60000}m)")
            return pick
        }

        // Иначе — первый непройденный из разблокированных
        val next = clusterDao.getNextCluster(now)
        if (next != null) {
            logger.d("Planner: next unmastered cluster ${next.id}")
            return next
        }

        logger.d("Planner: no more clusters — A1 completed!")
        return null
    }

    /**
     * Для выбранного кластера готовит полный набор данных, который
     * передаётся в Gemini через system_instruction.
     *
     * Включает:
     *   - леммы кластера (полные записи с артиклями)
     *   - 3-5 "слабых" лемм из предыдущих кластеров (на повтор)
     *   - правило грамматики, готовое к введению (если есть)
     *   - контекст уровня ученика
     */
    suspend fun prepareSessionContext(cluster: ClusterA1Entity): SessionContext {
        val lemmaList = parseLemmas(cluster.lemmasJson)
        val clusterLemmas = lemmaDao.getByLemmas(lemmaList)
        val weakLemmas = lemmaDao.getWeakestLemmas(limit = 5)
            .filter { it.lemma !in lemmaList } // не дублируем

        // Проверяем, не пора ли ввести новое правило
        val newRule = grammarDao.getNextRuleToIntroduce()

        return SessionContext(
            cluster = cluster,
            primaryLemmas = clusterLemmas,
            reviewLemmas = weakLemmas,
            grammarRuleToIntroduce = newRule,
        )
    }

    /**
     * Вызывается ПОСЛЕ завершения сессии.
     * 1) Обновляет кластер: mastery, attempts, next review
     * 2) Обновляет разблокировку зависимых кластеров
     * 3) Если было введено правило — отмечает это
     */
    suspend fun onSessionCompleted(
        cluster: ClusterA1Entity,
        overallQuality: Int,  // 1-7
        introducedRuleId: String?,
    ) {
        // 1. Обновляем сам кластер
        val masteryDelta = (overallQuality / 7f - 0.5f).coerceIn(-0.2f, 0.2f) // -0.2..+0.2
        val newMastery = (cluster.masteryScore + masteryDelta).coerceIn(0f, 1f)
        val nextReviewInterval = computeNextReviewInterval(newMastery)

        clusterDao.updateClusterProgress(
            id = cluster.id,
            masteryScore = newMastery,
            nextReview = System.currentTimeMillis() + nextReviewInterval,
        )
        logger.d("Planner: cluster ${cluster.id} mastery ${cluster.masteryScore} → $newMastery (next review in ${nextReviewInterval / 3_600_000}h)")

        // 2. Если кластер пересёк порог mastery — разблокируем зависимые
        if (newMastery >= 0.7f && cluster.masteryScore < 0.7f) {
            unlockDependentClusters(cluster.id)
        }

        // 3. Если было представлено правило
        if (introducedRuleId != null) {
            grammarDao.markIntroduced(introducedRuleId)
            logger.d("Planner: grammar rule $introducedRuleId marked as introduced")
        }
    }

    /**
     * Проходит по всем кластерам и разблокирует те, у которых
     * ВСЕ prerequisites теперь pass the threshold (0.7).
     */
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

    /** Вычислить интервал до следующего повтора на основе mastery. */
    private fun computeNextReviewInterval(mastery: Float): Long = when {
        mastery < 0.3f -> 4.hours.inWholeMilliseconds
        mastery < 0.5f -> 1.days.inWholeMilliseconds
        mastery < 0.7f -> 3.days.inWholeMilliseconds
        mastery < 0.9f -> 7.days.inWholeMilliseconds
        else           -> 21.days.inWholeMilliseconds
    }

    // ─── Helpers ───

    private fun parseLemmas(jsonStr: String): List<String> = try {
        Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(
                kotlinx.serialization.builtins.serializer<String>()
            ),
            jsonStr
        )
    } catch (_: Exception) { emptyList() }

    private fun parsePrerequisites(jsonStr: String): List<String> = parseLemmas(jsonStr)
}

/**
 * Полный контекст одной сессии, готовый для system_prompt.
 */
data class SessionContext(
    val cluster: ClusterA1Entity,
    val primaryLemmas: List<LemmaA1Entity>,
    val reviewLemmas: List<LemmaA1Entity>,
    val grammarRuleToIntroduce: GrammarRuleA1Entity?,
)
