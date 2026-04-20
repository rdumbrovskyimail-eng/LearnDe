// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/data/domain/A1SessionPlanner.kt
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
            logger.d("Planner: review cluster ${pick.id} (was due ${(now - (pick.nextReviewAt ?: now)) / 60000}m)")
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

    suspend fun prepareSessionContext(cluster: ClusterA1Entity): SessionContext {
        val lemmaList = parseLemmas(cluster.lemmasJson)
        val clusterLemmas = lemmaDao.getByLemmas(lemmaList)
        val weakLemmas = lemmaDao.getWeakestLemmas(limit = 5)
            .filter { it.lemma !in lemmaList }

        val newRule = grammarDao.getNextRuleToIntroduce()

        return SessionContext(
            cluster = cluster,
            primaryLemmas = clusterLemmas,
            reviewLemmas = weakLemmas,
            grammarRuleToIntroduce = newRule,
        )
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

    private fun computeNextReviewInterval(mastery: Float): Long = when {
        mastery < 0.3f -> 4.hours.inWholeMilliseconds
        mastery < 0.5f -> 1.days.inWholeMilliseconds
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