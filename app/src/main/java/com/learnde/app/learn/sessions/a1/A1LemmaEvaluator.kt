package com.learnde.app.learn.sessions.a1

import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.domain.ErrorDepth
import com.learnde.app.learn.data.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.FsrsRating
import com.learnde.app.learn.domain.FsrsScheduler
import com.learnde.app.learn.domain.FsrsState
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class A1LemmaEvaluator @Inject constructor(
    private val lemmaDao: A1LemmaDao,
    private val fsrs: FsrsScheduler,
    private val logger: AppLogger,
) {
    private val perLemmaLocks = ConcurrentHashMap<String, Mutex>()
    val snapshots = ConcurrentHashMap<String, FsrsState>()

    private fun lock(lemma: String) = perLemmaLocks.getOrPut(lemma) { Mutex() }

    suspend fun evaluate(
        lemma: String,
        quality: Int,
        diagnosis: ErrorDiagnosis,
        clusterId: String,
    ): EvalResult = lock(lemma).withLock {
        val entity = lemmaDao.getByLemma(lemma) ?: return@withLock EvalResult.UnknownLemma
        snapshots[lemma] = entity.toFsrsState()

        val adjusted = when (diagnosis.depth) {
            ErrorDepth.NONE, ErrorDepth.SLIP -> quality
            ErrorDepth.MISTAKE -> (quality - 1).coerceAtLeast(2)
            ErrorDepth.ERROR -> (quality - 2).coerceAtLeast(1)
        }
        val rating = FsrsRating.fromQuality(adjusted)
        val (newState, nextReviewAt) = fsrs.schedule(entity.toFsrsState(), rating)
        val newMastery = fsrs.masteryScore(newState)
        val wasCorrect = !diagnosis.isError
        val recognitionDelta = if (quality >= 4) 0.08f else 0.02f

        lemmaDao.updateProgressFsrs(
            lemma = lemma,
            produced = if (wasCorrect) 1 else 0,
            failed = if (!wasCorrect) 1 else 0,
            newProductionScore = newMastery,
            recognitionDelta = recognitionDelta,
            clusterId = clusterId,
            nextReview = nextReviewAt,
            fsrsDifficulty = newState.difficulty,
            fsrsStability = newState.stability,
            fsrsReps = newState.reps,
            fsrsLapses = newState.lapses,
            fsrsLastReviewAt = newState.lastReviewAt,
        )
        EvalResult.Updated(newMastery)
    }
}

sealed class EvalResult {
    data class Updated(val newMastery: Float) : EvalResult()
    object UnknownLemma : EvalResult()
}