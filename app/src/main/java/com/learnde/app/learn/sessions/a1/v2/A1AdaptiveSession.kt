package com.learnde.app.learn.sessions.a1.v2

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.learn.data.db.A1GrammarDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.A1SessionDao
import com.learnde.app.learn.data.db.A1SessionLogEntity
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.learn.data.db.v2.A1AssociationDao
import com.learnde.app.learn.data.db.v2.A1AssociationEntity
import com.learnde.app.learn.data.db.v2.LearnerProfileDao
import com.learnde.app.learn.domain.A1SessionPlanner
import com.learnde.app.learn.domain.ErrorCategory
import com.learnde.app.learn.domain.ErrorDepth
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.ErrorSource
import com.learnde.app.learn.domain.FsrsRating
import com.learnde.app.learn.domain.FsrsScheduler
import com.learnde.app.learn.domain.v2.A1PromptBuilderV2
import com.learnde.app.learn.domain.v2.LearnerSnapshot
import com.learnde.app.learn.domain.v2.LessonDirector
import com.learnde.app.learn.domain.v2.LessonScript
import com.learnde.app.learn.domain.v2.LessonScriptPlanner
import com.learnde.app.learn.sessions.a1.A1LearningBus
import com.learnde.app.learn.sessions.a1.A1LearningEvent
import com.learnde.app.learn.sessions.a1.A1Phase
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class AdaptiveMode { CLUSTER, REVIEW }

@Singleton
class A1AdaptiveSession @Inject constructor(
    private val planner: A1SessionPlanner,
    private val scriptPlanner: LessonScriptPlanner,
    private val director: LessonDirector,
    private val promptBuilder: A1PromptBuilderV2,
    private val systemTextBus: SystemTextBus,
    private val controlBus: SessionControlBus,
    private val lemmaDao: A1LemmaDao,
    private val grammarDao: A1GrammarDao,
    private val sessionDao: A1SessionDao,
    private val associationDao: A1AssociationDao,
    private val profileDao: LearnerProfileDao,
    private val fsrs: FsrsScheduler,
    private val bus: A1LearningBus,
    private val logger: AppLogger,
) : LearnSession {

    override val id: String = "a1_adaptive"

    @Volatile private var mode: AdaptiveMode = AdaptiveMode.CLUSTER
    @Volatile private var pendingScript: LessonScript? = null
    @Volatile private var snapshot: LearnerSnapshot = LearnerSnapshot(null, emptyList(), "", 0, 0)
    @Volatile private var cluster: ClusterA1Entity? = null
    @Volatile private var introducedRuleId: String? = null

    @Volatile private var sessionScope: CoroutineScope? = null
    @Volatile private var sessionStartedAt: Long = 0L
    @Volatile private var sessionCompleted: Boolean = false
    @Volatile private var evaluateCallsCount: Int = 0

    private val finishMutex = Mutex()
    private val perLemmaLocks = ConcurrentHashMap<String, Mutex>()
    private fun lemmaLock(lemma: String): Mutex = perLemmaLocks.getOrPut(lemma) { Mutex() }

    private val producedLemmas = ConcurrentHashMap.newKeySet<String>()
    private val failedLemmas = ConcurrentHashMap.newKeySet<String>()
    private val heardLemmas = ConcurrentHashMap.newKeySet<String>()
    private val diagnoses = ConcurrentHashMap<String, ErrorDiagnosis>()
    private val qualitySum = AtomicInteger(0)
    private val qualityCount = AtomicInteger(0)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepareForCluster(target: ClusterA1Entity) {
        mode = AdaptiveMode.CLUSTER
        cluster = target
        val context = planner.prepareSessionContext(target)
        pendingScript = scriptPlanner.buildScript(context)
        snapshot = buildSnapshot(pendingScript!!)
        logger.d("AdaptiveSession: prepared CLUSTER ${target.id}, ${pendingScript?.totalSteps} steps")
    }

    suspend fun prepareForReview(limit: Int = 15) {
        mode = AdaptiveMode.REVIEW
        cluster = null
        val lemmas = planner.pickReviewSessionLemmas(limit)
        pendingScript = scriptPlanner.buildReviewScript(lemmas)
        snapshot = buildSnapshot(pendingScript!!)
        logger.d("AdaptiveSession: prepared REVIEW, ${lemmas.size} lemmas")
    }

    @Volatile private var resumeRequested = false
    fun prepareResume() { resumeRequested = true }

    override val systemInstruction: String
        get() {
            val script = pendingScript ?: return fallbackPrompt()
            return promptBuilder.build(script, snapshot)
        }

    override val functionDeclarations: List<FunctionDeclarationConfig> =
        A1FunctionDeclarationsV2.ALL

    override val initialUserMessage: String
        get() = director.currentInstructionText()
            .ifBlank { "[СИСТЕМА]: Поздоровайся одной фразой и жди инструкции." }

    override suspend fun onEnter() {
        sessionScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sessionScope = scope

        sessionStartedAt = System.currentTimeMillis()
        sessionCompleted = false
        evaluateCallsCount = 0
        producedLemmas.clear(); failedLemmas.clear(); heardLemmas.clear()
        diagnoses.clear(); qualitySum.set(0); qualityCount.set(0)
        introducedRuleId = null

        val script = pendingScript
        when {
            resumeRequested -> {
                resumeRequested = false
                val resumed = director.tryResume(scope, systemTextBus::send)
                if (!resumed) {
                    logger.w("AdaptiveSession: resume requested but no active plan — fallback to pending script")
                    script?.let {
                        director.start(it, scope, systemTextBus::send, emitFirstInstruction = false)
                    }
                }
            }
            script != null ->
                director.start(script, scope, systemTextBus::send, emitFirstInstruction = false)
            else ->
                logger.e("AdaptiveSession.onEnter: NO SCRIPT prepared — session will idle")
        }

        bus.emit(A1LearningEvent.PhaseChanged(A1Phase.WARM_UP))
        logger.d("AdaptiveSession.onEnter (mode=$mode)")
    }

    override suspend fun onExit() {
        logger.d("AdaptiveSession.onExit (completed=$sessionCompleted, evals=$evaluateCallsCount)")
        if (!sessionCompleted && evaluateCallsCount > 0) {
            autoSaveLog(isComplete = false)
        }
        director.stop(discardPlan = false)
        sessionScope?.cancel()
        sessionScope = null
        pendingScript = null
    }

    override suspend fun handleToolCall(call: FunctionCall): String? = when (call.name) {
        A1FunctionDeclarationsV2.FN_STEP_DONE       -> handleStepDone(call)
        A1FunctionDeclarationsV2.FN_FLEX_MOMENT     -> director.onFlexMoment(call.args["reason"])
        A1FunctionDeclarationsV2.FN_LOG_ASSOCIATION -> handleLogAssociation(call)
        A1FunctionDeclarationsV2.FN_CONTROL_SESSION -> handleControl(call)
        A1FunctionDeclarationsV2.FN_UPDATE_PROFILE  -> handleUpdateProfile(call)
        A1FunctionDeclarationsV2.FN_EVALUATE        -> handleEvaluate(call)
        A1FunctionDeclarationsV2.FN_HEARD           -> handleHeard(call)
        A1FunctionDeclarationsV2.FN_PRODUCED        -> handleProduced(call)
        A1FunctionDeclarationsV2.FN_GRAMMAR         -> handleGrammar(call)
        A1FunctionDeclarationsV2.FN_FINISH          -> handleFinish(call)
        "start_phase"                               -> """{"status":"ok"}"""
        else -> null
    }

    private suspend fun handleStepDone(call: FunctionCall): String {
        val result = director.onStepDone(call.args["step_id"])
        director.state.value.currentStep?.kind?.let { kind ->
            bus.emit(A1LearningEvent.PhaseChanged(kind.toLegacyPhase()))
        }
        return result
    }

    private suspend fun handleLogAssociation(call: FunctionCall): String {
        val lemma = call.args["lemma"]?.trim().orEmpty()
        val text = call.args["association"]?.trim().orEmpty()
        if (lemma.isEmpty() || text.isEmpty()) return err("lemma and association required")
        associationDao.insert(A1AssociationEntity(lemma = lemma, text = text))
        logger.d("AdaptiveSession: association saved '$lemma' → '$text'")
        return """{"status":"ok","note":"association saved for future lessons"}"""
    }

    private suspend fun handleControl(call: FunctionCall): String {
        val action = SessionControlAction.fromString(call.args["action"])
            ?: return err("unknown action")
        if (action == SessionControlAction.SKIP_STEP) {
            director.skipCurrentStep()
        }
        controlBus.emit(action, byVoice = true)
        logger.d("AdaptiveSession: voice control → $action")
        return """{"status":"ok","action":"$action"}"""
    }

    private suspend fun handleUpdateProfile(call: FunctionCall): String {
        val name = call.args["name"]?.trim().orEmpty()
        val interests = call.args["interests"]?.trim().orEmpty()
        if (name.isNotEmpty()) profileDao.updateName(name)
        if (interests.isNotEmpty()) {
            val existing = profileDao.get()?.interestsCsv.orEmpty()
            val merged = (existing.split(',') + interests.split(','))
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                .joinToString(", ")
            profileDao.updateInterests(merged)
        }
        return ok()
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

        val intervention = diagnosis.recommendedIntervention()
        bus.emitSuspend(
            A1LearningEvent.LemmaEvaluated(lemma, quality, diagnosis, intervention, feedback)
        )

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
            val recognitionDelta = if (quality >= 4) 0.08f else 0.02f

            lemmaDao.updateProgressFsrs(
                lemma = lemma,
                produced = if (wasCorrect) 1 else 0,
                failed = if (!wasCorrect) 1 else 0,
                newProductionScore = newMastery,
                recognitionDelta = recognitionDelta,
                clusterId = cluster?.id ?: "review",
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

    private suspend fun handleHeard(call: FunctionCall): String {
        val lemma = call.args["lemma"]?.trim() ?: return err("no lemma")
        heardLemmas.add(lemma)
        bus.emit(A1LearningEvent.LemmaHeard(lemma))
        runCatching { lemmaDao.markHeard(lemma, System.currentTimeMillis()) }
        return ok()
    }

    private suspend fun handleProduced(call: FunctionCall): String {
        val lemma = call.args["lemma"]?.trim() ?: return err("no lemma")
        val quality = call.args["quality"]?.toIntOrNull()?.coerceIn(1, 7) ?: 5
        producedLemmas.add(lemma)
        bus.emit(A1LearningEvent.LemmaProduced(lemma, quality))
        return ok()
    }

    private suspend fun handleGrammar(call: FunctionCall): String {
        val ruleId = call.args["rule_id"]?.trim() ?: return err("no rule_id")
        introducedRuleId = ruleId
        runCatching { grammarDao.markIntroduced(ruleId) }
        val rule = runCatching { grammarDao.getById(ruleId) }.getOrNull()
        bus.emit(A1LearningEvent.GrammarIntroduced(ruleId, rule?.nameRu ?: ruleId))
        return ok()
    }

    private suspend fun handleFinish(call: FunctionCall): String = finishMutex.withLock {
        if (sessionCompleted) return@withLock ok()
        val quality = call.args["overall_quality"]?.toIntOrNull()?.coerceIn(1, 7) ?: 5
        val feedback = call.args["feedback"] ?: ""

        sessionCompleted = true
        autoSaveLog(isComplete = true, finalQuality = quality, finalFeedback = feedback)

        cluster?.let { c ->
            runCatching {
                planner.onSessionCompleted(c, quality, introducedRuleId)
            }.onFailure { logger.e("AdaptiveSession: onSessionCompleted failed: ${it.message}", it) }
        }

        director.onSessionFinished()
        bus.emitSuspend(A1LearningEvent.SessionFinished(quality, feedback))
        bus.emitSuspend(A1LearningEvent.PhaseChanged(A1Phase.FINISHED))
        ok()
    }

    private suspend fun buildSnapshot(script: LessonScript): LearnerSnapshot {
        val profile = runCatching { profileDao.get() }.getOrNull()
        val lemmasOfLesson = script.newLemmas + script.reviewLemmas
        val associations = runCatching {
            associationDao.getForLemmas(lemmasOfLesson)
        }.getOrDefault(emptyList())

        return LearnerSnapshot(
            profile = profile,
            associations = associations,
            frequentErrorsSummary = buildErrorSummary(),
            masteredCount = runCatching { lemmaDao.getMasteredCount() }.getOrDefault(0),
            streakDays = 0,
        )
    }

    private suspend fun buildErrorSummary(): String = runCatching {
        val recent = sessionDao.getRecent(limit = 10)
        val counter = HashMap<String, Int>()
        for (log in recent) {
            val obj = runCatching {
                json.parseToJsonElement(log.errorDiagnosesJson).jsonObject
            }.getOrNull() ?: continue
            for ((_, diag) in obj) {
                val d = runCatching { diag.jsonObject }.getOrNull() ?: continue
                val cat = d["category"]?.jsonPrimitive?.content ?: continue
                val src = d["source"]?.jsonPrimitive?.content ?: "NONE"
                if (cat == "NONE") continue
                val key = if (src != "NONE") "$cat ($src)" else cat
                counter[key] = (counter[key] ?: 0) + 1
            }
        }
        counter.entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString(" · ") { "${it.key}: ${it.value}" }
    }.getOrDefault("")

    private suspend fun autoSaveLog(
        isComplete: Boolean,
        finalQuality: Int? = null,
        finalFeedback: String? = null,
    ) {
        val endedAt = System.currentTimeMillis()
        val avgQ = if (qualityCount.get() == 0) 0f
                   else qualitySum.get().toFloat() / qualityCount.get()
        val qualityValue = finalQuality ?: avgQ.toInt().coerceIn(1, 7)
        val feedbackValue = finalFeedback
            ?: "Урок прерван. Отработано ${producedLemmas.size + failedLemmas.size} слов."

        val targeted = pendingScript?.let { it.newLemmas + it.reviewLemmas } ?: emptyList()

        val diagnosesJson = runCatching {
            buildJsonObject {
                diagnoses.forEach { (lemma, d) ->
                    put(lemma, buildJsonObject {
                        put("source", JsonPrimitive(d.source.name))
                        put("depth", JsonPrimitive(d.depth.name))
                        put("category", JsonPrimitive(d.category.name))
                        put("specifics", JsonPrimitive(d.specifics))
                    })
                }
            }.toString()
        }.getOrDefault("{}")

        val phaseReached = director.state.value.currentStep?.kind?.toLegacyPhase()?.name
            ?: if (isComplete) A1Phase.FINISHED.name else A1Phase.DRILL.name

        runCatching {
            sessionDao.insert(
                A1SessionLogEntity(
                    clusterId = cluster?.id ?: "review",
                    startedAt = sessionStartedAt,
                    endedAt = endedAt,
                    lemmasTargetedJson = json.encodeToString(targeted),
                    lemmasProducedJson = json.encodeToString(producedLemmas.toList()),
                    lemmasFailedJson = json.encodeToString(failedLemmas.toList()),
                    overallQuality = qualityValue,
                    feedbackText = feedbackValue,
                    grammarRuleIntroduced = introducedRuleId,
                    isComplete = isComplete,
                    phaseReached = phaseReached,
                    errorDiagnosesJson = diagnosesJson,
                    avgQuality = avgQ,
                    evaluateCallsCount = evaluateCallsCount,
                )
            )
        }.onFailure { logger.e("AdaptiveSession: log save failed: ${it.message}", it) }
        logger.d("AdaptiveSession: log saved (complete=$isComplete)")
    }

    private fun fallbackPrompt(): String =
        "Ты — Лина, тёплый русскоязычный репетитор немецкого A1. " +
        "Скрипт урока не загрузился. Скажи ученику по-русски, что урок не " +
        "удалось подготовить и нужно вернуться на главный экран, затем " +
        "вызови finish_session(overall_quality=4, feedback=\"Технический сбой подготовки\")."

    private fun ok() = """{"status":"ok"}"""
    private fun err(msg: String) = """{"error":"$msg"}"""
}

private fun com.learnde.app.learn.domain.v2.StepKind.toLegacyPhase(): A1Phase = when (this) {
    com.learnde.app.learn.domain.v2.StepKind.GREETING       -> A1Phase.WARM_UP
    com.learnde.app.learn.domain.v2.StepKind.RECALL_OLD     -> A1Phase.WARM_UP
    com.learnde.app.learn.domain.v2.StepKind.INTRODUCE,
    com.learnde.app.learn.domain.v2.StepKind.ECHO           -> A1Phase.INTRODUCE
    com.learnde.app.learn.domain.v2.StepKind.RETRIEVE_NEW,
    com.learnde.app.learn.domain.v2.StepKind.USE_IN_CONTEXT,
    com.learnde.app.learn.domain.v2.StepKind.FINAL_RECALL   -> A1Phase.DRILL
    com.learnde.app.learn.domain.v2.StepKind.FLEX,
    com.learnde.app.learn.domain.v2.StepKind.MICRO_DIALOG   -> A1Phase.APPLY
    com.learnde.app.learn.domain.v2.StepKind.GRAMMAR_SPOT   -> A1Phase.GRAMMAR
    com.learnde.app.learn.domain.v2.StepKind.WRAP_UP        -> A1Phase.COOL_DOWN
}