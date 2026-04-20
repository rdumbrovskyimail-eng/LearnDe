// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1SituationSession.kt
//
// LearnSession для обучения A1 (не тестирования!).
//
// Принцип работы:
//   1. ViewModel перед стартом вызывает prepareForCluster(id).
//   2. Session подгружает SessionContext из планировщика.
//   3. Строит dynamic system_instruction через PromptBuilder.
//   4. Во время сессии обрабатывает function calls от Gemini:
//        - start_phase → шлёт в Bus для UI
//        - mark_lemma_heard → incrementExposure грамматики
//        - evaluate_and_update_lemma → updates БД
//        - introduce_grammar_rule → marks introduced
//        - finish_session → вызов планировщика onSessionCompleted
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
import com.learnde.app.learn.domain.SessionContext
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) : LearnSession {

    override val id: String = "a1_situation"

    // Текущее состояние сессии — устанавливается через prepareForCluster()
    @Volatile private var currentContext: SessionContext? = null
    @Volatile private var sessionStartedAt: Long = 0L

    private val mutex = Mutex()
    private val targetedLemmas = ConcurrentHashMap.newKeySet<String>()
    private val producedLemmas = ConcurrentHashMap.newKeySet<String>()
    private val failedLemmas = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var introducedRuleId: String? = null

    /**
     * ВЫЗЫВАЕТСЯ ИЗ VIEWMODEL ПЕРЕД START.
     * Готовит контекст: подтягивает кластер, слабые леммы, грамматику.
     */
    suspend fun prepareForCluster(cluster: ClusterA1Entity) {
        val ctx = planner.prepareSessionContext(cluster)
        currentContext = ctx
        targetedLemmas.clear()
        producedLemmas.clear()
        failedLemmas.clear()
        introducedRuleId = null
        logger.d("A1Session: prepared context for cluster ${cluster.id}")
    }

    // ─── LearnSession interface ───

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
        logger.d("A1Session onExit")
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

    // ─── Handlers ───

    private fun handleStartPhase(call: FunctionCall): String {
        val phaseStr = call.args["phase"] ?: return err("no phase")
        val phase = runCatching { A1Phase.valueOf(phaseStr) }.getOrElse { A1Phase.IDLE }
        logger.d("A1Session: phase → $phase")
        bus.emit(A1LearningEvent.PhaseChanged(phase))
        return ok()
    }

    private suspend fun handleMarkLemmaHeard(call: FunctionCall): String {
        val lemma = call.args["lemma"]?.trim() ?: return err("no lemma")
        targetedLemmas.add(lemma)

        // Инкрементируем экспозицию грамматики, если лемма связана с правилом
        val ctx = currentContext
        if (ctx != null) {
            val grammarFocus = ctx.cluster.grammarFocus
            findGrammarRuleIdByFocus(grammarFocus)?.let { ruleId ->
                grammarDao.incrementExposure(ruleId, delta = 1)
            }
        }

        bus.emit(A1LearningEvent.LemmaHeard(lemma))
        return ok()
    }

    private suspend fun handleMarkLemmaProduced(call: FunctionCall): String {
        val lemma = call.args["lemma"]?.trim() ?: return err("no lemma")
        val quality = call.args["quality"]?.toIntOrNull()?.coerceIn(1, 7) ?: 5

        producedLemmas.add(lemma)
        // Лёгкий апдейт — полное обновление идёт в evaluate
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

    private suspend fun handleEvaluateAndUpdate(call: FunctionCall): String {
        val lemma = call.args["lemma"]?.trim() ?: return err("no lemma")
        val quality = call.args["quality"]?.toIntOrNull()?.coerceIn(1, 7) ?: 5
        val wasCorrect = call.args["was_produced_correctly"]?.toBooleanStrictOrNull() ?: false
        val feedback = call.args["feedback"] ?: ""

        if (wasCorrect) producedLemmas.add(lemma)
        else failedLemmas.add(lemma)

        // Главный апдейт прогресса
        val productionDelta = when (quality) {
            7 -> 0.15f
            6 -> 0.10f
            5 -> 0.05f
            4 -> 0.0f
            3 -> -0.03f
            2 -> -0.05f
            else -> -0.08f
        }
        val recognitionDelta = if (quality >= 4) 0.08f else 0.02f
        val clusterId = currentContext?.cluster?.id ?: "unknown"

        lemmaDao.updateProgress(
            lemma = lemma,
            produced = if (wasCorrect) 1 else 0,
            failed = if (!wasCorrect) 1 else 0,
            productionDelta = productionDelta,
            recognitionDelta = recognitionDelta,
            clusterId = clusterId,
            nextReview = computeNextReviewForLemma(quality),
        )

        bus.emit(A1LearningEvent.LemmaEvaluated(lemma, quality, wasCorrect, feedback))
        return ok()
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

        // 1. Обновляем прогресс кластера через планировщик
        planner.onSessionCompleted(ctx.cluster, quality, introducedRuleId)

        // 2. Логируем сессию
        val jsonList = { list: Collection<String> ->
            Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    kotlinx.serialization.builtins.serializer<String>()
                ),
                list.toList()
            )
        }
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
            )
        )

        // 3. Событие в UI
        bus.emit(A1LearningEvent.SessionFinished(quality, feedback))
        bus.emit(A1LearningEvent.PhaseChanged(A1Phase.FINISHED))

        logger.d("A1Session finished: cluster=${ctx.cluster.id} quality=$quality produced=${producedLemmas.size} failed=${failedLemmas.size}")
        ok()
    }

    // ─── helpers ───

    private fun computeNextReviewForLemma(quality: Int): Long {
        val base = System.currentTimeMillis()
        val days = when (quality) {
            7 -> 7
            6 -> 3
            5 -> 1
            4 -> 1
            else -> 0
        }
        return if (days == 0) base + 4 * 3_600_000L  // 4 часа для слабых
        else base + days.days.inWholeMilliseconds
    }

    /** Эвристика — найти правило по грамматическому фокусу кластера. */
    private suspend fun findGrammarRuleIdByFocus(focus: String): String? {
        val rules = A1GrammarCatalog.RULES
        return rules.firstOrNull { rule ->
            focus.contains(rule.nameDe, ignoreCase = true) ||
                rule.nameDe.contains(focus.take(15), ignoreCase = true)
        }?.id
    }

    private fun ok() = """{"status":"ok"}"""
    private fun err(msg: String) = """{"error":"$msg"}"""

    companion object {
        private const val DEFAULT_SYSTEM_INSTRUCTION =
            "Ты — немецкий учитель. Ученик открыл сессию без выбранного кластера. " +
                "Скажи по-русски: 'Выбери урок из списка и нажми Старт'. Завершись."
    }
}
