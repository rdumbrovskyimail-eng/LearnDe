// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1SituationSession.kt
//
// ИЗМЕНЕНИЯ:
//   - handleEvaluateAndUpdate теперь парсит ErrorDiagnosis из Gemini
//   - Вычисляет Intervention
//   - Эмитит расширенный LemmaEvaluated с диагностикой
//   - disputeEvaluation обновлён для новой модели
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1GrammarDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.A1SessionDao
import com.learnde.app.learn.data.db.A1SessionLogEntity
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.learn.data.grammar.A1GrammarCatalog
import com.learnde.app.learn.domain.A1SessionPlanner
import com.learnde.app.learn.domain.A1SystemPromptBuilder
import com.learnde.app.learn.domain.ErrorCategory
import com.learnde.app.learn.domain.ErrorDepth
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.ErrorSource
import com.learnde.app.learn.domain.SessionContext
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days

@Singleton
class A1SituationSession @Inject constructor(
    private val planner: A1SessionPlanner,
    private val promptBuilder: A1SystemPromptBuilder,
    private val lemmaDao: A1LemmaDao,
    private val clusterDao: A1ClusterDao,
    private val grammarDao: A1GrammarDao,
    private val sessionDao: A1SessionDao,
    private val bus: A1LearningBus,
    private val logger: AppLogger,
    private val fsrs: com.learnde.app.learn.domain.FsrsScheduler,
) : LearnSession {

    override val id: String = "a1_situation"

    @Volatile private var currentContext: SessionContext? = null
    @Volatile private var sessionStartedAt: Long = 0L

    private val mutex = Mutex()
    private val targetedLemmas = ConcurrentHashMap.newKeySet<String>()
    private val producedLemmas = ConcurrentHashMap.newKeySet<String>()
    private val failedLemmas = ConcurrentHashMap.newKeySet<String>()

    // Диагностика по каждой лемме сессии — для аналитики и дебага
    private val diagnoses = ConcurrentHashMap<String, ErrorDiagnosis>()

    @Volatile private var introducedRuleId: String? = null
    @Volatile private var currentPhase: A1Phase = A1Phase.IDLE
    @Volatile private var evaluateCallsCount: Int = 0
    private val qualityAccumulator = mutableListOf<Int>()

    suspend fun disputeEvaluation(lemma: String) {
        if (failedLemmas.remove(lemma)) {
            producedLemmas.add(lemma)
            diagnoses[lemma] = ErrorDiagnosis() // NONE/NONE/NONE
            val clusterId = currentContext?.cluster?.id ?: "unknown"
            lemmaDao.updateProgress(
                lemma = lemma,
                produced = 1,
                failed = -1,
                productionDelta = 0.15f,
                recognitionDelta = 0.05f,
                clusterId = clusterId,
                nextReview = System.currentTimeMillis() + 7L * 24 * 3600 * 1000
            )
            logger.d("A1Session: Disputed evaluation for $lemma")
        }
    }

    suspend fun prepareForCluster(cluster: ClusterA1Entity) {
        val ctx = planner.prepareSessionContext(cluster)
        currentContext = ctx
        targetedLemmas.clear()
        producedLemmas.clear()
        failedLemmas.clear()
        diagnoses.clear()
        introducedRuleId = null
        currentPhase = A1Phase.IDLE
        evaluateCallsCount = 0
        qualityAccumulator.clear()
        logger.d("A1Session: prepared context for cluster ${cluster.id}")
    }

    override val systemInstruction: String
        get() {
            val ctx = currentContext ?: return DEFAULT_SYSTEM_INSTRUCTION
            return promptBuilder.build(ctx)
        }

    override val functionDeclarations: List<FunctionDeclarationConfig> =
        A1FunctionDeclarations.ALL

    override val initialUserMessage: String =
        "[СИСТЕМА]: Ученик готов. Начинай с фазы WARM_UP по шаблону."

    override suspend fun onEnter() {
        sessionStartedAt = System.currentTimeMillis()
        logger.d("A1Session onEnter: cluster=${currentContext?.cluster?.id}")
        bus.emit(A1LearningEvent.PhaseChanged(A1Phase.IDLE))
    }

    override suspend fun onExit() {
        logger.d("A1Session onExit (phase=$currentPhase, evaluateCalls=$evaluateCallsCount)")
        // Patch 2.5: если finish_session не вызывался, но были evaluate —
        // автосохраняем incomplete-сессию.
        if (currentPhase != A1Phase.FINISHED && evaluateCallsCount > 0) {
            autoSaveIncompleteSession()
        }
    }

    private suspend fun autoSaveIncompleteSession() {
        val ctx = currentContext ?: return
        val endedAt = System.currentTimeMillis()
        val avgQ = if (qualityAccumulator.isEmpty()) 0f
                   else qualityAccumulator.average().toFloat()
        val rawQuality = avgQ.toInt().coerceIn(1, 7)

        logger.w("A1Session: AUTOSAVING incomplete session (cluster=${ctx.cluster.id})")

        // Всё же обновим mastery кластера — иначе пользователь прошёл 80%
        // и не получил ничего. Даём пропорциональный бонус.
        val progressFraction = when (currentPhase) {
            A1Phase.IDLE, A1Phase.WARM_UP -> 0.1f
            A1Phase.INTRODUCE -> 0.3f
            A1Phase.DRILL -> 0.6f
            A1Phase.APPLY -> 0.8f
            A1Phase.GRAMMAR -> 0.9f
            A1Phase.COOL_DOWN, A1Phase.FINISHED -> 1.0f
        }
        // Снижаем "штраф" за прерывание — масштабируем quality
        val adjustedQuality = (rawQuality * progressFraction).toInt().coerceAtLeast(1)

        planner.onSessionCompleted(ctx.cluster, adjustedQuality, introducedRuleId)

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
        } catch (e: Exception) { "{}" }

        sessionDao.insert(
            A1SessionLogEntity(
                clusterId = ctx.cluster.id,
                startedAt = sessionStartedAt,
                endedAt = endedAt,
                lemmasTargetedJson = jsonList(targetedLemmas),
                lemmasProducedJson = jsonList(producedLemmas),
                lemmasFailedJson = jsonList(failedLemmas),
                overallQuality = adjustedQuality,
                feedbackText = "Сессия прервана на фазе $currentPhase. Прогресс частично засчитан.",
                grammarRuleIntroduced = introducedRuleId,
                isComplete = false,
                phaseReached = currentPhase.name,
                errorDiagnosesJson = diagnosesJson,
                avgQuality = avgQ,
                evaluateCallsCount = evaluateCallsCount,
            )
        )

        // Эмитим псевдо-SessionFinished чтобы UI закрыл сессию аккуратно
        bus.emit(A1LearningEvent.SessionFinished(
            overallQuality = adjustedQuality,
            feedback = "Сессия прервана. Засчитано ${(progressFraction * 100).toInt()}% прогресса."
        ))
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        return when (call.name) {
            A1FunctionDeclarations.FN_START_PHASE -> handleStartPhase(call)
            A1FunctionDeclarations.FN_MARK_LEMMA_HEARD -> handleMarkLemmaHeard(call)
            A1FunctionDeclarations.FN_MARK_LEMMA_PRODUCED -> handleMarkLemmaProduced(call)
            A1FunctionDeclarations.FN_EVALUATE_AND_UPDATE -> handleEvaluateAndUpdate(call)
            A1FunctionDeclarations.FN_INTRODUCE_GRAMMAR -> handleIntroduceGrammar(call)
            A1FunctionDeclarations.FN_FINISH_SESSION -> handleFinishSession(call)
            else -> null
        }
    }

    private fun handleStartPhase(call: FunctionCall): String {
        val phaseStr = call.args["phase"] ?: return err("no phase")
        val phase = runCatching { A1Phase.valueOf(phaseStr) }.getOrElse { A1Phase.IDLE }
        currentPhase = phase
        logger.d("A1Session: phase → $phase")
        bus.emit(A1LearningEvent.PhaseChanged(phase))
        return ok()
    }

    private suspend fun handleMarkLemmaHeard(call: FunctionCall): String {
        val lemma = call.args["lemma"]?.trim() ?: return err("no lemma")
        targetedLemmas.add(lemma)

        val ctx = currentContext
        if (ctx != null) {
            // v3.1: сначала пробуем прямую ссылку grammarRuleId, затем фоллбэк
            val ruleId = ctx.cluster.grammarRuleId?.takeIf { it.isNotBlank() }
                ?: findGrammarRuleIdByFocus(ctx.cluster.grammarFocus)
            ruleId?.let { grammarDao.incrementExposure(it, delta = 1) }
        }

        bus.emit(A1LearningEvent.LemmaHeard(lemma))
        return ok()
    }

    private suspend fun handleMarkLemmaProduced(call: FunctionCall): String {
        val lemma = call.args["lemma"]?.trim() ?: return err("no lemma")
        val quality = call.args["quality"]?.toIntOrNull()?.coerceIn(1, 7) ?: 5

        producedLemmas.add(lemma)
        val delta = (quality - 3).coerceIn(-2, 4) * 0.03f
        val clusterId = currentContext?.cluster?.id ?: "unknown"
        lemmaDao.updateProgress(
            lemma = lemma,
            produced = 1,
            failed = 0,
            productionDelta = delta,
            recognitionDelta = 0.02f,
            clusterId = clusterId,
            nextReview = computeNextReviewForLemma(quality),
        )
        bus.emit(A1LearningEvent.LemmaProduced(lemma, quality))
        return ok()
    }

    // ═══════════════════════════════════════════════════════════
    //  EVALUATE_AND_UPDATE — теперь с диагностикой Selinker
    // ═══════════════════════════════════════════════════════════
    private suspend fun handleEvaluateAndUpdate(call: FunctionCall): String {
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
        qualityAccumulator.add(quality)

        val wasCorrect = !diagnosis.isError
        if (wasCorrect) producedLemmas.add(lemma) else failedLemmas.add(lemma)

        val intervention = diagnosis.recommendedIntervention()

        // ═══ Patch 3: FSRS-5 scheduling ═══
        val entity = lemmaDao.getByLemma(lemma)
        if (entity == null) {
            logger.w("A1Session.eval: lemma '$lemma' not in DB (Gemini сочинил?)")
            return """{"status":"ignored","reason":"unknown lemma"}"""
        }

        // Корректируем rating с учётом глубины ошибки:
        // SLIP не штрафуется полностью — бампим rating вверх.
        val adjustedQuality = when (diagnosis.depth) {
            ErrorDepth.SLIP -> (quality + 2).coerceAtMost(7)
            ErrorDepth.MISTAKE -> (quality + 1).coerceAtMost(7)
            else -> quality
        }
        val rating = com.learnde.app.learn.domain.FsrsRating.fromQuality(adjustedQuality)

        val (newFsrsState, nextReviewAt) = fsrs.schedule(entity.toFsrsState(), rating)
        val newMastery = fsrs.masteryScore(newFsrsState)

        logger.d(
            "A1Session.eval[FSRS]: lemma=$lemma q=$quality(adj=$adjustedQuality) " +
            "rating=$rating src=${diagnosis.source} depth=${diagnosis.depth} " +
            "→ mastery=$newMastery, next in ${(nextReviewAt - System.currentTimeMillis()) / 3600_000}h"
        )

        val recognitionDelta = if (quality >= 4) 0.08f else 0.02f
        val clusterId = currentContext?.cluster?.id ?: "unknown"

        lemmaDao.updateProgressFsrs(
            lemma = lemma,
            produced = if (wasCorrect) 1 else 0,
            failed = if (!wasCorrect) 1 else 0,
            newProductionScore = newMastery,
            recognitionDelta = recognitionDelta,
            clusterId = clusterId,
            nextReview = nextReviewAt,
            fsrsDifficulty = newFsrsState.difficulty,
            fsrsStability = newFsrsState.stability,
            fsrsReps = newFsrsState.reps,
            fsrsLapses = newFsrsState.lapses,
            fsrsLastReviewAt = newFsrsState.lastReviewAt,
        )

        bus.emit(A1LearningEvent.LemmaEvaluated(
            lemma = lemma,
            quality = quality,
            diagnosis = diagnosis,
            intervention = intervention,
            feedback = feedback,
        ))
        return """{"status":"ok","intervention":"$intervention","mastery":"$newMastery"}"""
    }



    private suspend fun handleIntroduceGrammar(call: FunctionCall): String {
        val ruleId = call.args["rule_id"]?.trim() ?: return err("no rule_id")
        introducedRuleId = ruleId

        val rule = grammarDao.getById(ruleId)
        if (rule == null) {
            logger.w("A1Session: unknown grammar rule_id=$ruleId")
            return err("rule not found")
        }

        bus.emit(A1LearningEvent.GrammarIntroduced(ruleId, rule.nameRu))
        return ok()
    }

    private suspend fun handleFinishSession(call: FunctionCall): String = mutex.withLock {
        val quality = call.args["overall_quality"]?.toIntOrNull()?.coerceIn(1, 7) ?: 5
        val feedback = call.args["feedback"] ?: ""
        val ctx = currentContext ?: return@withLock err("no active context")

        val endedAt = System.currentTimeMillis()

        planner.onSessionCompleted(ctx.cluster, quality, introducedRuleId)

        val jsonList = { list: Collection<String> ->
            Json.encodeToString(list.toList())
        }
        val avgQ = if (qualityAccumulator.isEmpty()) quality.toFloat()
                   else qualityAccumulator.average().toFloat()
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
        } catch (e: Exception) { "{}" }

        sessionDao.insert(
            A1SessionLogEntity(
                clusterId = ctx.cluster.id,
                startedAt = sessionStartedAt,
                endedAt = endedAt,
                lemmasTargetedJson = jsonList(targetedLemmas),
                lemmasProducedJson = jsonList(producedLemmas),
                lemmasFailedJson = jsonList(failedLemmas),
                overallQuality = quality,
                feedbackText = feedback,
                grammarRuleIntroduced = introducedRuleId,
                isComplete = true,
                phaseReached = A1Phase.FINISHED.name,
                errorDiagnosesJson = diagnosesJson,
                avgQuality = avgQ,
                evaluateCallsCount = evaluateCallsCount,
            )
        )
        currentPhase = A1Phase.FINISHED

        // Логируем сводку диагностики
        val errorSummary = diagnoses.values
            .filter { it.isError }
            .groupingBy { "${it.source}/${it.category}" }
            .eachCount()
        logger.d("A1Session finished: errors summary = $errorSummary")

        bus.emit(A1LearningEvent.SessionFinished(quality, feedback))
        bus.emit(A1LearningEvent.PhaseChanged(A1Phase.FINISHED))

        ok()
    }

    private fun computeNextReviewForLemma(quality: Int): Long {
        val base = System.currentTimeMillis()
        val days = when (quality) {
            7 -> 7
            6 -> 3
            5 -> 1
            4 -> 1
            else -> 0
        }
        return if (days == 0) base + 4 * 3_600_000L
        else base + days.days.inWholeMilliseconds
    }

    private suspend fun findGrammarRuleIdByFocus(focus: String): String? {
        if (focus.isBlank()) return null
        val f = focus.lowercase()
        val ruleId = when {
            "akkusativ" in f && ("präposition" in f || "für" in f || "ohne" in f || "gegen" in f) -> "g21_praeposition_akkusativ"
            "akkusativ" in f -> "g08_akkusativ"
            "dativ" in f || "mit " in f || " zu" in f || " bei" in f -> "g14_praeposition_dativ"
            "trennbar" in f || "aufstehen" in f || "anrufen" in f -> "g13_trennbare_verben"
            "modal" in f || "können" in f || "möchten" in f -> "g12_modalverben"
            "plural" in f -> "g11_plural"
            "possessiv" in f -> "g10_possessiv"
            "negation" in f || "nicht" in f || "kein" in f -> "g09_negation"
            "artikel" in f -> "g07_artikel_nominativ"
            "uhr" in f || "zeit" in f -> "g15_uhrzeit"
            "datum" in f || "monat" in f -> "g16_datum"
            "satzbau" in f || "position" in f -> "g16_satzbau"
            "imperativ" in f || "komm!" in f -> "g17_imperativ"
            "gern" in f || "lieber" in f -> "g18_gern_lieber"
            "weil" in f -> "g19_weil"
            "perfekt" in f -> "g20_perfekt_basics"
            "adjektiv" in f && "prädikat" in f -> "g22_adjektiv_nach_sein"
            "pronomen" in f && ("mich" in f || "dich" in f) -> "g23_pronomen_akk"
            "personalpronomen" in f -> "g01_personalpronomen_nom"
            "sein-konjugation" in f || "sein " in f -> "g02_sein_praesens"
            "haben" in f -> "g03_haben_praesens"
            "w-frage" in f || "wer" in f || "wo" in f -> "g05_w_fragen"
            "zahl" in f || "kardinal" in f -> "g06_zahlen_basis"
            "alphabet" in f -> "g00_alphabet"
            "höflichkeit" in f || "soziale" in f -> "g00_greetings"
            "verb-konjugation" in f || "präsens" in f -> "g04_regulaere_verben"
            else -> null
        }
        if (ruleId == null) {
            logger.d("findGrammarRuleIdByFocus: no match for '$focus'")
        }
        return ruleId
    }

    private fun ok() = """{"status":"ok"}"""
    private fun err(msg: String) = """{"error":"$msg"}"""

    companion object {
        private const val DEFAULT_SYSTEM_INSTRUCTION =
            "Ты — немецкий учитель. Ученик открыл сессию без выбранного кластера. " +
                "Скажи по-русски: 'Выбери урок из списка и нажми Старт'. Завершись."
    }
}