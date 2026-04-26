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
import com.learnde.app.learn.domain.A1SessionPlanner
import com.learnde.app.learn.domain.A1SystemPromptBuilder
import com.learnde.app.learn.domain.ErrorCategory
import com.learnde.app.learn.domain.ErrorDepth
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.ErrorSource
import com.learnde.app.learn.domain.SessionContext
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var currentContext: SessionContext? = null
    @Volatile private var sessionStartedAt: Long = 0L

    private val mutex = Mutex()
    private val perLemmaLocks = ConcurrentHashMap<String, Mutex>()
    private fun lemmaLock(lemma: String): Mutex =
        perLemmaLocks.getOrPut(lemma) { Mutex() }

    private val targetedLemmas = ConcurrentHashMap.newKeySet<String>()
    private val producedLemmas = ConcurrentHashMap.newKeySet<String>()
    private val failedLemmas = ConcurrentHashMap.newKeySet<String>()

    private val diagnoses = ConcurrentHashMap<String, ErrorDiagnosis>()

    @Volatile private var introducedRuleId: String? = null
    @Volatile private var currentPhase: A1Phase = A1Phase.IDLE
    @Volatile private var evaluateCallsCount: Int = 0
    
    /** Снапшоты FSRS-состояний "до" последней оценки — для отката при dispute. */
    private val preEvalSnapshots = ConcurrentHashMap<String, com.learnde.app.learn.domain.FsrsState>()
    
    // Заменяем CopyOnWriteArrayList на структуру с поддержкой замены последнего значения леммы.
    private val qualityAccumulator = CopyOnWriteArrayList<Pair<String, Int>>() // lemma to quality

    private fun normalizeLemma(raw: String): String {
        var s = raw.trim()
        val lowerS = s.lowercase()
        val articles = listOf("der ", "die ", "das ", "den ", "dem ", "des ", "ein ", "eine ", "einen ", "einem ", "einer ")
        for (a in articles) {
            if (lowerS.startsWith(a)) {
                s = s.substring(a.length).trim()
                break
            }
        }
        val greeters = listOf("guten ", "guter ", "auf ")
        val lowerS2 = s.lowercase()
        for (g in greeters) {
            if (lowerS2.startsWith(g)) {
                s = s.substring(g.length).trim()
                break
            }
        }
        return s.lowercase()
    }

    suspend fun disputeEvaluation(lemma: String) {
        val norm = normalizeLemma(lemma)
        val wasFailed = failedLemmas.remove(norm) || 
                        failedLemmas.removeIf { it.equals(norm, ignoreCase = true) }
        if (!wasFailed) {
            logger.d("A1Session: dispute for '$norm' but it wasn't in failedLemmas")
            return
        }
        
        producedLemmas.add(norm)
        diagnoses[norm] = ErrorDiagnosis()
        
        // ФИКС: Заменяем последнюю оценку этой леммы в qualityAccumulator на 7.
        val lastIdx = qualityAccumulator.indexOfLast { 
            it.first.equals(norm, ignoreCase = true) 
        }
        if (lastIdx >= 0) {
            qualityAccumulator[lastIdx] = norm to 7
            logger.d("A1Session: dispute updated quality for '$norm' to 7 (was ${qualityAccumulator[lastIdx].second})")
        }
        
        val clusterId = currentContext?.cluster?.id ?: "unknown"
        
        val snapshot = preEvalSnapshots[norm]
        if (snapshot != null) {
            // Откатываемся на состояние ДО ошибочной оценки и применяем оценку GOOD от него.
            val rating = com.learnde.app.learn.domain.FsrsRating.fromQuality(7)
            val (newState, nextReviewAt) = fsrs.schedule(snapshot, rating)
            val newMastery = fsrs.masteryScore(newState)
            lemmaDao.updateProgressFsrs(
                lemma = norm,
                produced = 1,
                failed = -1,
                newProductionScore = newMastery,
                // ФИКС: Добавляем ровно 0.06f, так как 0.02f уже были начислены при первоначальной (ошибочной) оценке (0.08 - 0.02 = 0.06)
                recognitionDelta = 0.06f,
                clusterId = clusterId,
                nextReview = nextReviewAt,
                fsrsDifficulty = newState.difficulty,
                fsrsStability = newState.stability,
                fsrsReps = newState.reps,
                fsrsLapses = newState.lapses,
                fsrsLastReviewAt = newState.lastReviewAt,
            )
            preEvalSnapshots.remove(norm)
        } else {
            logger.w("A1Session: dispute for '$norm' — no snapshot available, skipping FSRS rollback")
        }
        
        logger.d("A1Session: Disputed evaluation for $norm")
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
        if (currentPhase != A1Phase.FINISHED && evaluateCallsCount > 0) {
            autoSaveIncompleteSession()
        }
        // ФИКС: Очищаем состояние синглтона
        currentContext = null
        targetedLemmas.clear()
        producedLemmas.clear()
        failedLemmas.clear()
        diagnoses.clear()
        qualityAccumulator.clear()
        preEvalSnapshots.clear()
    }

    private suspend fun autoSaveIncompleteSession() {
        val ctx = currentContext ?: return
        val endedAt = System.currentTimeMillis()
        val avgQ = if (qualityAccumulator.isEmpty()) 0f
                   else qualityAccumulator.map { it.second }.average().toFloat()
        val rawQuality = avgQ.toInt().coerceIn(1, 7)

        logger.w("A1Session: AUTOSAVING incomplete session (cluster=${ctx.cluster.id})")

        val progressFraction = when (currentPhase) {
            A1Phase.IDLE, A1Phase.WARM_UP -> 0.1f
            A1Phase.INTRODUCE -> 0.3f
            A1Phase.DRILL -> 0.6f
            A1Phase.APPLY -> 0.8f
            A1Phase.GRAMMAR -> 0.9f
            A1Phase.COOL_DOWN, A1Phase.FINISHED -> 1.0f
        }
        val adjustedQuality = (rawQuality * progressFraction).toInt().coerceAtLeast(1)

        planner.onSessionCompleted(ctx.cluster, adjustedQuality, introducedRuleId)

        val jsonList = { list: Collection<String> -> Json.encodeToString(list.toList()) }
        // ФИКС: Явное построение JsonObject защищает от крашей при обфускации R8 в релизе
        val diagnosesJson = try {
            val jsonObject = kotlinx.serialization.json.buildJsonObject {
                diagnoses.forEach { (lemma, d) ->
                    put(lemma, kotlinx.serialization.json.buildJsonObject {
                        put("source", kotlinx.serialization.json.JsonPrimitive(d.source.name))
                        put("depth", kotlinx.serialization.json.JsonPrimitive(d.depth.name))
                        put("category", kotlinx.serialization.json.JsonPrimitive(d.category.name))
                        put("specifics", kotlinx.serialization.json.JsonPrimitive(d.specifics))
                    })
                }
            }
            jsonObject.toString()
        } catch (_: Exception) { "{}" }

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

        bus.emitSuspend(A1LearningEvent.SessionFinished(
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

    private suspend fun handleStartPhase(call: FunctionCall): String {
        val phaseStr = call.args["phase"] ?: return err("no phase")
        val phase = runCatching { A1Phase.valueOf(phaseStr) }.getOrElse { A1Phase.IDLE }
        currentPhase = phase
        logger.d("A1Session: phase → $phase")
        bus.emitSuspend(A1LearningEvent.PhaseChanged(phase))
        return ok()
    }

    private suspend fun handleMarkLemmaHeard(call: FunctionCall): String {
        val lemma = normalizeLemma(call.args["lemma"] ?: return err("no lemma"))
        targetedLemmas.add(lemma)

        val ctx = currentContext
        scope.launch {
            // ФИКС: Инкрементируем timesHeard в БД.
            lemmaDao.incrementTimesHeard(lemma)
            
            if (ctx != null) {
                // ФИКС: Полагаемся строго на связь в БД. Убираем опасный хардкод-fallback.
                val ruleId = ctx.cluster.grammarRuleId?.takeIf { it.isNotBlank() }
                ruleId?.let { grammarDao.incrementExposure(it, delta = 1) }
            }
            bus.emit(A1LearningEvent.LemmaHeard(lemma))
        }
        
        return ok()
    }

    private suspend fun handleMarkLemmaProduced(call: FunctionCall): String {
        val lemma = normalizeLemma(call.args["lemma"] ?: return err("no lemma"))
        val quality = call.args["quality"]?.toIntOrNull()?.coerceIn(1, 7) ?: 5

        producedLemmas.add(lemma)
        // ФИКС: Добавляем оценку в аккумулятор, чтобы она учитывалась при расчете среднего балла за сессию
        qualityAccumulator.add(lemma to quality)

        val delta = (quality - 3).coerceIn(-2, 4) * 0.03f
        val clusterId = currentContext?.cluster?.id ?: "unknown"
        
        scope.launch {
            // КРИТИЧНО: Используем updateProgressNoReschedule чтобы НЕ ломать FSRS-расписание.
            // mark_lemma_produced — это лёгкое подтверждение использования слова в речи,
            // оно НЕ должно перезаписывать интервалы повторения, рассчитанные FSRS.
            lemmaDao.updateProgressNoReschedule(
                lemma = lemma,
                produced = 1,
                failed = 0,
                productionDelta = delta,
                recognitionDelta = 0.02f,
                clusterId = clusterId,
            )
            bus.emit(A1LearningEvent.LemmaProduced(lemma, quality))
        }
        return ok()
    }

    private suspend fun handleEvaluateAndUpdate(call: FunctionCall): String {
        val originalLemma = call.args["lemma"] ?: return err("no lemma")
        val lemma = normalizeLemma(originalLemma)
        if (lemma != originalLemma) {
            logger.d("A1Session: normalized lemma '$originalLemma' → '$lemma'")
        }
        
        val quality = call.args["quality"]?.toIntOrNull()?.coerceIn(1, 7) ?: 5
        val feedback = call.args["feedback"] ?: ""
        val errorDepthStr = call.args["error_depth"] ?: "NONE"

        // GUARD: Защита от ASR-мусора
        val isAsrGarbage = originalLemma.length < 3 || run {
            val hasLatin = originalLemma.any { it in 'a'..'z' || it in 'A'..'Z' || it in "äöüßÄÖÜ" }
            val hasCyrillic = originalLemma.any { it in 'а'..'я' || it in 'А'..'Я' }
            !hasLatin && !hasCyrillic
        }
        
        if ((errorDepthStr == "ERROR" || errorDepthStr == "MISTAKE") && isAsrGarbage) {
            logger.w("A1Session.eval: rejecting bogus $errorDepthStr due to ASR garbage: '$originalLemma'")
            return """{"status":"skipped","reason":"asr_garbage"}"""
        }

        val diagnosis = ErrorDiagnosis(
            source = ErrorSource.fromString(call.args["error_source"]),
            depth = ErrorDepth.fromString(call.args["error_depth"]),
            category = ErrorCategory.fromString(call.args["error_category"]),
            specifics = call.args["error_specifics"] ?: "",
        )
        diagnoses[lemma] = diagnosis
        evaluateCallsCount++
        qualityAccumulator.add(lemma to quality)

        val wasCorrect = !diagnosis.isError
        if (wasCorrect) producedLemmas.add(lemma) else failedLemmas.add(lemma)

        return lemmaLock(lemma).withLock {
            val entity = lemmaDao.getByLemma(lemma)
            val intervention = diagnosis.recommendedIntervention()
            if (entity == null) {
                logger.w("A1Session.eval: lemma '$lemma' (raw='$originalLemma') not in DB (Gemini сочинил?)")
                bus.emitSuspend(A1LearningEvent.LemmaEvaluated(
                    lemma = lemma, quality = quality, diagnosis = diagnosis,
                    intervention = intervention, feedback = feedback,
                ))
                return@withLock """{"status":"ignored","reason":"unknown lemma","intervention":"$intervention"}"""
            }

            preEvalSnapshots[lemma] = entity.toFsrsState()

            val adjustedQuality = when (diagnosis.depth) {
                ErrorDepth.NONE, ErrorDepth.SLIP -> quality
                ErrorDepth.MISTAKE -> (quality - 1).coerceAtLeast(2)
                ErrorDepth.ERROR -> (quality - 2).coerceAtLeast(1)
            }
            val rating = com.learnde.app.learn.domain.FsrsRating.fromQuality(adjustedQuality)
            val (newFsrsState, nextReviewAt) = fsrs.schedule(entity.toFsrsState(), rating)
            val newMastery = fsrs.masteryScore(newFsrsState)
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
            bus.emitSuspend(A1LearningEvent.LemmaEvaluated(
                lemma = lemma, quality = quality, diagnosis = diagnosis,
                intervention = intervention, feedback = feedback,
            ))
            """{"status":"ok","intervention":"$intervention","mastery":"$newMastery"}"""
        }
    }

    private suspend fun handleIntroduceGrammar(call: FunctionCall): String {
        val ruleId = call.args["rule_id"]?.trim() ?: return err("no rule_id")
        introducedRuleId = ruleId

        val rule = grammarDao.getById(ruleId)
        if (rule == null) {
            logger.w("A1Session: unknown grammar rule_id=$ruleId")
            return err("rule not found")
        }

        bus.emitSuspend(A1LearningEvent.GrammarIntroduced(ruleId, rule.nameRu))
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
                   else qualityAccumulator.map { it.second }.average().toFloat()
        // ФИКС: Явное построение JsonObject защищает от крашей при обфускации R8 в релизе
        val diagnosesJson = try {
            val jsonObject = kotlinx.serialization.json.buildJsonObject {
                diagnoses.forEach { (lemma, d) ->
                    put(lemma, kotlinx.serialization.json.buildJsonObject {
                        put("source", kotlinx.serialization.json.JsonPrimitive(d.source.name))
                        put("depth", kotlinx.serialization.json.JsonPrimitive(d.depth.name))
                        put("category", kotlinx.serialization.json.JsonPrimitive(d.category.name))
                        put("specifics", kotlinx.serialization.json.JsonPrimitive(d.specifics))
                    })
                }
            }
            jsonObject.toString()
        } catch (_: Exception) { "{}" }

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

        bus.emitSuspend(A1LearningEvent.SessionFinished(quality, feedback))
        bus.emitSuspend(A1LearningEvent.PhaseChanged(A1Phase.FINISHED))

        ok()
    }


    private fun ok() = """{"status":"ok"}"""
    private fun err(msg: String) = """{"error":"$msg"}"""

    companion object {
        private const val DEFAULT_SYSTEM_INSTRUCTION =
            "Ты — немецкий учитель. Ученик открыл сессию без выбранного кластера. " +
                "Скажи по-русски: 'Выбери урок из списка и нажми Старт'. Завершись."
    }
}