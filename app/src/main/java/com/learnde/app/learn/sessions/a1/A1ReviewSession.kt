package com.learnde.app.learn.sessions.a1

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1GrammarDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.A1SessionDao
import com.learnde.app.learn.data.db.A1SessionLogEntity
import com.learnde.app.learn.data.db.LemmaA1Entity
import com.learnde.app.learn.domain.A1SessionPlanner
import com.learnde.app.learn.domain.ErrorCategory
import com.learnde.app.learn.domain.ErrorDepth
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.ErrorSource
import com.learnde.app.learn.domain.FsrsRating
import com.learnde.app.learn.domain.FsrsScheduler
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class A1ReviewSession @Inject constructor(
    private val planner: A1SessionPlanner,
    private val lemmaDao: A1LemmaDao,
    private val clusterDao: A1ClusterDao,
    private val grammarDao: A1GrammarDao,
    private val sessionDao: A1SessionDao,
    private val bus: A1LearningBus,
    private val logger: AppLogger,
    private val fsrs: FsrsScheduler,
) : LearnSession {

    override val id: String = "a1_review"

    @Volatile private var reviewLemmas: List<LemmaA1Entity> = emptyList()
    @Volatile private var sessionStartedAt: Long = 0L

    private val mutex = Mutex()
    private val perLemmaLocks = ConcurrentHashMap<String, Mutex>()
    private fun lemmaLock(lemma: String): Mutex =
        perLemmaLocks.getOrPut(lemma) { Mutex() }

    private val producedLemmas = ConcurrentHashMap.newKeySet<String>()
    private val failedLemmas = ConcurrentHashMap.newKeySet<String>()
    private val diagnoses = ConcurrentHashMap<String, ErrorDiagnosis>()

    private val qualitySum = java.util.concurrent.atomic.AtomicInteger(0)
    private val qualityCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var evaluateCallsCount: Int = 0

    override val systemInstruction: String
        get() = buildReviewPrompt()

    override val functionDeclarations: List<FunctionDeclarationConfig> =
        listOf(
            A1FunctionDeclarations.EVALUATE_AND_UPDATE_DECL,
            A1FunctionDeclarations.FINISH_SESSION_DECL,
        )

    override val initialUserMessage: String =
        "[СИСТЕМА]: Ученик готов к быстрому повторению. Начинай сразу с первого слова."

    suspend fun prepareLemmas(limit: Int = 15) {
        reviewLemmas = planner.pickReviewSessionLemmas(limit)
        logger.d("A1ReviewSession: prepared ${reviewLemmas.size} lemmas for review")
    }

    private fun buildReviewPrompt(): String {
        if (reviewLemmas.isEmpty()) {
            return """
                Ты — репетитор A1. Список слов для повторения пуст.
                Скажи по-русски: "Отличная работа! Сейчас нет слов для повторения. Возвращайся позже."
                Сразу вызови finish_session(overall_quality=7, feedback="Нет слов для повторения").
            """.trimIndent()
        }

        val wordList = reviewLemmas.joinToString("\n") { lemma ->
            val art = lemma.article?.let { "$it " } ?: ""
            "  • $art${lemma.lemma} [${lemma.pos}]"
        }

        val allowedLemmas = reviewLemmas.joinToString(", ") { it.lemma }

        return """
════════════════════════════════════════════════════════════
РОЛЬ: Русскоязычный репетитор немецкого A1.
РЕЖИМ: БЛИЦ-ОПРОС (БЫСТРОЕ ПОВТОРЕНИЕ).
════════════════════════════════════════════════════════════

🚨🚨🚨 ПРАВИЛА БЛИЦ-ОПРОСА (СКОРОСТЬ — ГЛАВНОЕ) 🚨🚨🚨

1. МГНОВЕННЫЙ СТАРТ: Получив системное сообщение, СРАЗУ скажи: "Повторяем слова. Начнём!" и задай первый вопрос.
2. ПУЛЕМЕТНЫЙ ТЕМП: 1 короткий вопрос → ответ ученика → 1 короткая реакция (Верно/Неверно + правильный ответ) → СРАЗУ следующий вопрос.
3. БЕЗ БОЛТОВНИ: Запрещено хвалить длинными фразами. Запрещено объяснять грамматику. Только перевод слов или коротких фраз.
4. СНАЧАЛА ГОЛОС: Сначала произнеси реакцию и следующий вопрос вслух, и ТОЛЬКО ПОТОМ вызывай `evaluate_and_update_lemma`.

════════════════════════════════════════════════════════════
📚 СЛОВА ДЛЯ ПОВТОРЕНИЯ (${reviewLemmas.size} слов):
$wordList

🔒 ИСПОЛЬЗУЙ ТОЛЬКО ЭТИ СЛОВА!
════════════════════════════════════════════════════════════

🔬 ДИАГНОСТИКА:
После каждого ответа ученика ОБЯЗАТЕЛЬНО вызывай `evaluate_and_update_lemma` со всеми 5 полями (error_source, error_depth, error_category, specifics, feedback).

ФИНАЛ:
Когда спросишь все слова из списка, скажи 1 фразу итога и вызови `finish_session(overall_quality=N, feedback="...")`.

ЖДИ СИСТЕМНОГО СООБЩЕНИЯ И СТАРТУЙ!
        """.trimIndent()
    }

    override suspend fun onEnter() {
        sessionStartedAt = System.currentTimeMillis()
        producedLemmas.clear()
        failedLemmas.clear()
        diagnoses.clear()
        qualitySum.set(0)
        qualityCount.set(0)
        evaluateCallsCount = 0
        logger.d("A1ReviewSession onEnter: ${reviewLemmas.size} lemmas to review")
        bus.emit(A1LearningEvent.PhaseChanged(A1Phase.DRILL))
    }

    override suspend fun onExit() {
        logger.d("A1ReviewSession onExit (evaluateCalls=$evaluateCallsCount)")
        if (evaluateCallsCount > 0) {
            autoSaveSession(isComplete = false)
        }
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        return when (call.name) {
            A1FunctionDeclarations.FN_EVALUATE_AND_UPDATE -> handleEvaluate(call)
            A1FunctionDeclarations.FN_FINISH_SESSION -> handleFinish(call)
            else -> """{"error":"function not available in review mode"}"""
        }
    }

    private suspend fun handleEvaluate(call: FunctionCall): String {
        val lemma = call.args["lemma"]?.trim() ?: return err("no lemma")
        val quality = call.args["quality"]?.toIntOrNull()?.coerceIn(1, 7) ?: 5
        val feedback = call.args["feedback"] ?: ""

        val diagnosis = ErrorDiagnosis(
            source = ErrorSource.fromString(call.args["error_source"]),
            depth = ErrorDepth.fromString(call.args["error_depth"]),
            category = ErrorCategory.fromString(call.args["error_category"]),
            specifics = call.args["error_specifics"] ?: "",
        )
        diagnoses[lemma] = diagnosis
        evaluateCallsCount++
        qualitySum.addAndGet(quality)
        qualityCount.incrementAndGet()

        val wasCorrect = !diagnosis.isError
        if (wasCorrect) producedLemmas.add(lemma) else failedLemmas.add(lemma)

        return lemmaLock(lemma).withLock {
            val entity = lemmaDao.getByLemma(lemma)
                ?: return@withLock """{"status":"ignored","reason":"unknown lemma"}"""

            val adjustedQuality = when (diagnosis.depth) {
                ErrorDepth.NONE, ErrorDepth.SLIP -> quality
                ErrorDepth.MISTAKE -> (quality - 1).coerceAtLeast(2)
                ErrorDepth.ERROR -> (quality - 2).coerceAtLeast(1)
            }
            val rating = FsrsRating.fromQuality(adjustedQuality)
            val (newState, nextReviewAt) = fsrs.schedule(entity.toFsrsState(), rating)
            val newMastery = fsrs.masteryScore(newState)
            val intervention = diagnosis.recommendedIntervention()
            val recognitionDelta = if (quality >= 4) 0.08f else 0.02f

            lemmaDao.updateProgressFsrs(
                lemma = lemma,
                produced = if (wasCorrect) 1 else 0,
                failed = if (!wasCorrect) 1 else 0,
                newProductionScore = newMastery,
                recognitionDelta = recognitionDelta,
                clusterId = "review",
                nextReview = nextReviewAt,
                fsrsDifficulty = newState.difficulty,
                fsrsStability = newState.stability,
                fsrsReps = newState.reps,
                fsrsLapses = newState.lapses,
                fsrsLastReviewAt = newState.lastReviewAt,
            )

            """{"status":"ok","intervention":"$intervention","mastery":"$newMastery"}"""
        }
    }

    private suspend fun handleFinish(call: FunctionCall): String = mutex.withLock {
        val quality = call.args["overall_quality"]?.toIntOrNull()?.coerceIn(1, 7) ?: 5
        val feedback = call.args["feedback"] ?: ""

        autoSaveSession(isComplete = true, finalQuality = quality, finalFeedback = feedback)

        bus.emitSuspend(A1LearningEvent.SessionFinished(quality, feedback))
        bus.emitSuspend(A1LearningEvent.PhaseChanged(A1Phase.FINISHED))
        ok()
    }

    private suspend fun autoSaveSession(
        isComplete: Boolean,
        finalQuality: Int? = null,
        finalFeedback: String? = null,
    ) {
        val endedAt = System.currentTimeMillis()
        val avgQ = if (qualityCount.get() == 0) 0f
                   else qualitySum.get().toFloat() / qualityCount.get()
        val qualityValue = finalQuality ?: avgQ.toInt().coerceIn(1, 7)
        val feedbackValue = finalFeedback ?: "Повторено ${producedLemmas.size + failedLemmas.size} слов."

        val jsonList = { list: Collection<String> -> Json.encodeToString(list.toList()) }
        val diagnosesJson = try {
            Json.encodeToString(
                diagnoses.mapValues { (_, d) ->
                    mapOf(
                        "source" to d.source.name,
                        "depth" to d.depth.name,
                        "category" to d.category.name,
                        "specifics" to d.specifics,
                    )
                }
            )
        } catch (_: Exception) { "{}" }

        sessionDao.insert(
            A1SessionLogEntity(
                clusterId = "review",
                startedAt = sessionStartedAt,
                endedAt = endedAt,
                lemmasTargetedJson = jsonList(reviewLemmas.map { it.lemma }),
                lemmasProducedJson = jsonList(producedLemmas),
                lemmasFailedJson = jsonList(failedLemmas),
                overallQuality = qualityValue,
                feedbackText = feedbackValue,
                grammarRuleIntroduced = null,
                isComplete = isComplete,
                phaseReached = if (isComplete) A1Phase.FINISHED.name else A1Phase.DRILL.name,
                errorDiagnosesJson = diagnosesJson,
                avgQuality = avgQ,
                evaluateCallsCount = evaluateCallsCount,
            )
        )
        logger.d("A1ReviewSession: saved log (complete=$isComplete, produced=${producedLemmas.size}, failed=${failedLemmas.size})")
    }

    private fun ok() = """{"status":"ok"}"""
    private fun err(msg: String) = """{"error":"$msg"}"""
}