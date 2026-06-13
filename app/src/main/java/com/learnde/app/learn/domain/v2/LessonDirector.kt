package com.learnde.app.learn.domain.v2

import com.learnde.app.learn.data.db.v2.A1LessonPlanDao
import com.learnde.app.learn.data.db.v2.LessonPlanStateEntity
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Канал «наружу»: единственный способ Director'а говорить с моделью. */
fun interface DirectorOutbound {
    fun sendSystemText(text: String)
}

/** Состояние для UI (единый экран Studio читает только это). */
data class DirectorState(
    val active: Boolean = false,
    val planId: String? = null,
    val clusterTitleRu: String = "",
    val cursor: Int = 0,
    val totalSteps: Int = 0,
    val currentStep: LessonStep? = null,
    val steps: List<LessonStep> = emptyList(),
    val flexUntilMs: Long = 0L,
    val newLemmas: List<String> = emptyList(),
) {
    val progress: Float
        get() = if (totalSteps == 0) 0f else (cursor.toFloat() / totalSteps).coerceIn(0f, 1f)
    val isFlexNow: Boolean
        get() = flexUntilMs > System.currentTimeMillis() ||
            currentStep?.kind == StepKind.FLEX
}

sealed class DirectorEvent {
    data class StepAdvanced(val completed: LessonStep, val next: LessonStep?) : DirectorEvent()
    data class ScriptFinished(val planId: String, val clusterId: String) : DirectorEvent()
    data class NudgeSent(val level: Int, val stepId: String) : DirectorEvent()
}

@Singleton
class LessonDirector @Inject constructor(
    private val planDao: A1LessonPlanDao,
    private val flexPolicy: FlexTalkPolicy,
    private val logger: AppLogger,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(DirectorState())
    val state: StateFlow<DirectorState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DirectorEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<DirectorEvent> = _events.asSharedFlow()

    private val mutex = Mutex()
    @Volatile private var script: LessonScript? = null
    @Volatile private var outbound: DirectorOutbound? = null
    @Volatile private var scope: CoroutineScope? = null
    private var nudgeJob: Job? = null

    /** Сколько мягких напоминаний уже отправлено на текущем шаге. */
    @Volatile private var nudgesOnCurrentStep = 0

    /** До какого момента напоминания заморожены (flex_moment / FLEX-шаг). */
    @Volatile private var flexFrozenUntil = 0L

    // ─────────────────────────────────────────────────────────────
    //  Жизненный цикл
    // ─────────────────────────────────────────────────────────────

    /**
     * Запуск нового урока. Скрипт активирует первый шаг, персистится,
     * первая инструкция уходит модели.
     */
    /**
     * @param emitFirstInstruction false, если первую инструкцию доставит
     *        сам клиент как initialUserMessage (см. A1AdaptiveSession) —
     *        тогда Director её не дублирует.
     */
    suspend fun start(
        newScript: LessonScript,
        scope: CoroutineScope,
        outbound: DirectorOutbound,
        emitFirstInstruction: Boolean = true,
    ) = mutex.withLock {
        this.scope = scope
        this.outbound = outbound
        nudgesOnCurrentStep = 0
        flexFrozenUntil = 0L

        // Инвариант «один активный план»: старые незакрытые планы гасим.
        runCatching { planDao.markAllFinished() }

        val activated = newScript.withCurrentActivated()
        script = activated
        persist(activated, finished = false)
        publish(activated)
        logger.d("Director: start ${activated.planId} (${activated.totalSteps} steps)")

        if (emitFirstInstruction) sendStepInstruction(activated)
        restartNudgeTimer()
    }

    /**
     * Текст инструкции ТЕКУЩЕГО шага — для initialUserMessage клиента.
     * При reconnect клиент перечитает это свойство и модель получит
     * актуальный шаг, а не первый → бесшовный resume.
     */
    fun currentInstructionText(): String {
        val s = script ?: return ""
        val step = s.currentStep ?: return ""
        return if (s.cursor == 0) formatStep(s, step)
        else resumeInstruction() ?: formatStep(s, step)
    }

    /**
     * Возобновление после крэша/разрыва: достаём незавершённый план
     * из Room. Возвращает true если есть что возобновлять.
     */
    suspend fun tryResume(scope: CoroutineScope, outbound: DirectorOutbound): Boolean = mutex.withLock {
        val saved = planDao.getActivePlan() ?: return@withLock false
        val restored = runCatching {
            json.decodeFromString<LessonScript>(saved.scriptJson)
        }.getOrNull() ?: run {
            planDao.markFinished(saved.planId)
            return@withLock false
        }
        if (restored.isFinished) {
            planDao.markFinished(saved.planId)
            return@withLock false
        }

        this.scope = scope
        this.outbound = outbound
        nudgesOnCurrentStep = 0
        flexFrozenUntil = 0L

        val activated = restored.withCurrentActivated()
        script = activated
        publish(activated)
        logger.d("Director: RESUMED ${activated.planId} at step ${activated.cursor + 1}/${activated.totalSteps}")
        return@withLock true
    }

    /**
     * Лёгкая проверка «есть ли в Room незавершённый план» — БЕЗ подъёма
     * его в активное состояние. Нужна BlindModeController'у, чтобы решить:
     * возобновлять прерванный урок или планировать новый.
     */
    suspend fun tryPeekActivePlanExists(): Boolean =
        runCatching { planDao.getActivePlan() != null }.getOrDefault(false)

    /**
     * Инструкция возврата для модели после reconnect — отправляется
     * сессией как initialUserMessage / системный текст.
     */
    fun resumeInstruction(): String? {
        val s = script ?: return null
        val step = s.currentStep ?: return null
        return "[СИСТЕМА]: Связь восстановлена. Урок «${s.clusterTitleRu}» " +
            "продолжается с шага ${s.cursor + 1} из ${s.totalSteps}. " +
            "НЕ здоровайся заново, скажи коротко «продолжаем» и выполни шаг:\n" +
            formatStep(s, step)
    }

    /** Полная остановка (выход из урока). Незавершённый план остаётся в Room для resume. */
    suspend fun stop(discardPlan: Boolean = false) = mutex.withLock {
        nudgeJob?.cancel(); nudgeJob = null
        if (discardPlan) {
            script?.let { planDao.markFinished(it.planId) }
        }
        script = null
        outbound = null
        _state.value = DirectorState()
        logger.d("Director: stopped (discard=$discardPlan)")
    }

    // ─────────────────────────────────────────────────────────────
    //  Входящие сигналы от модели (через сессию)
    // ─────────────────────────────────────────────────────────────

    /**
     * step_done от модели. Принимается ТОЛЬКО id текущего шага
     * (или пустой id — трактуем как «текущий»). Всё остальное —
     * идемпотентно поглощается с ok-ответом, чтобы не ломать диалог.
     *
     * @return JSON-результат для tool response.
     */
    suspend fun onStepDone(stepId: String?): String = mutex.withLock {
        val s = script ?: return@withLock """{"status":"ignored","reason":"no active lesson"}"""
        val current = s.currentStep
            ?: return@withLock """{"status":"ignored","reason":"lesson already finished"}"""

        val effectiveId = stepId?.trim().orEmpty()
        if (effectiveId.isNotEmpty() && effectiveId != current.id) {
            // Дубликат/устаревший/выдуманный id → поглощаем, напоминаем истину.
            logger.w("Director: step_done('$effectiveId') != current '${current.id}' — absorbed")
            return@withLock """{"status":"ok","note":"already past that step","current_step":"${current.id}"}"""
        }

        advanceLocked(skipped = false)
        val next = script?.currentStep
        return@withLock if (next != null) {
            """{"status":"ok","next_step":"${next.id}"}"""
        } else {
            """{"status":"ok","lesson_complete":true}"""
        }
    }

    /**
     * Модель заявила отступление (ученик задал вопрос / хочет поболтать).
     * Замораживаем напоминания на flex-бюджет.
     */
    fun onFlexMoment(reason: String?): String {
        val budget = flexPolicy.digressionBudgetMs
        flexFrozenUntil = System.currentTimeMillis() + budget
        nudgesOnCurrentStep = 0
        publishCurrent()
        logger.d("Director: flex_moment('${reason ?: ""}') — nudges frozen ${budget / 1000}s")
        restartNudgeTimer()
        return """{"status":"ok","flex_seconds":${budget / 1000}}"""
    }

    /**
     * Ход модели завершён (TurnComplete) — перезапускаем таймер
     * напоминаний от свежей точки активности.
     */
    fun onModelTurnComplete() {
        restartNudgeTimer()
    }

    /** Принудительный пропуск шага (кнопкой UI или голосовой командой «дальше»). */
    suspend fun skipCurrentStep(): String = mutex.withLock {
        val s = script ?: return@withLock """{"status":"ignored"}"""
        val current = s.currentStep ?: return@withLock """{"status":"ignored"}"""
        logger.d("Director: SKIP step ${current.id} (${current.kind})")
        advanceLocked(skipped = true)
        return@withLock """{"status":"ok"}"""
    }

    /** Урок закрыт finish_session — помечаем план завершённым. */
    suspend fun onSessionFinished() = mutex.withLock {
        nudgeJob?.cancel(); nudgeJob = null
        val s = script ?: return@withLock
        planDao.markFinished(s.planId)
        _events.tryEmit(DirectorEvent.ScriptFinished(s.planId, s.clusterId))
        script = null
        _state.value = DirectorState()
        logger.d("Director: session finished, plan ${s.planId} closed")
    }

    // ─────────────────────────────────────────────────────────────
    //  Внутреннее: продвижение и напоминания
    // ─────────────────────────────────────────────────────────────

    /** Вызывать ТОЛЬКО под mutex. */
    private suspend fun advanceLocked(skipped: Boolean) {
        val s = script ?: return
        val completed = s.currentStep ?: return

        var advanced = s.withCurrentCompleted(skipped = skipped)
        val finishedAll = advanced.isFinished
        if (!finishedAll) advanced = advanced.withCurrentActivated()

        script = advanced
        nudgesOnCurrentStep = 0
        flexFrozenUntil = 0L
        persist(advanced, finished = finishedAll)
        publish(advanced)

        _events.tryEmit(DirectorEvent.StepAdvanced(completed, advanced.currentStep))
        logger.d(
            "Director: ${completed.id} ${if (skipped) "SKIPPED" else "DONE"} → " +
            "cursor ${advanced.cursor}/${advanced.totalSteps}"
        )

        if (!finishedAll) {
            sendStepInstruction(advanced)
            restartNudgeTimer()
        } else {
            nudgeJob?.cancel(); nudgeJob = null
            // WRAP_UP сам вызывает finish_session; ScriptFinished
            // прилетит из onSessionFinished(). Если модель забудет —
            // страховка ниже (см. wrapUpSafety в A1AdaptiveSession).
        }
    }

    private fun sendStepInstruction(s: LessonScript) {
        val step = s.currentStep ?: return
        outbound?.sendSystemText(formatStep(s, step))
    }

    private fun formatStep(s: LessonScript, step: LessonStep): String =
        "[ШАГ ${s.cursor + 1}/${s.totalSteps} | ${step.kind.name} | id=${step.id}]\n" +
        step.instruction

    /**
     * Таймер мягких напоминаний. Уровни:
     *   0 → softLimit: «когда будет естественная пауза — вернись к шагу»
     *   1 → softLimit + 45с: чуть настойчивее
     *   2 → hardLimit: повторная отправка полной инструкции шага
     * FLEX-заморозка (flexFrozenUntil) откладывает любые уровни.
     */
    private fun restartNudgeTimer() {
        val sc = scope ?: return
        nudgeJob?.cancel()
        nudgeJob = sc.launch {
            while (true) {
                val s = script ?: return@launch
                val step = s.currentStep ?: return@launch
                val now = System.currentTimeMillis()

                val frozenLeft = flexFrozenUntil - now
                if (frozenLeft > 0) { delay(frozenLeft); continue }

                val elapsed = now - step.activatedAt
                val nextDeadline = when (nudgesOnCurrentStep) {
                    0 -> step.kind.softLimitMs
                    1 -> step.kind.softLimitMs + 45_000L
                    else -> step.kind.hardLimitMs
                }
                val waitMs = nextDeadline - elapsed
                if (waitMs > 0) { delay(waitMs); continue }

                val currentAfterWait = script?.currentStep
                if (currentAfterWait?.id != step.id) continue // шаг уже сменился

                when (nudgesOnCurrentStep) {
                    0, 1 -> {
                        outbound?.sendSystemText(flexPolicy.softReminder(step, nudgesOnCurrentStep))
                        _events.tryEmit(DirectorEvent.NudgeSent(nudgesOnCurrentStep, step.id))
                        nudgesOnCurrentStep++
                        logger.d("Director: soft nudge #$nudgesOnCurrentStep on ${step.id}")
                    }
                    else -> {
                        // Жёсткий якорь: полная инструкция заново.
                        outbound?.sendSystemText(
                            "[СИСТЕМА]: Возвращаемся к графику. Текущий шаг:\n" +
                                formatStep(s, step)
                        )
                        _events.tryEmit(DirectorEvent.NudgeSent(2, step.id))
                        nudgesOnCurrentStep++
                        logger.w("Director: HARD re-anchor on ${step.id}")
                        delay(step.kind.hardLimitMs) // следующий якорь через ещё один hardLimit
                    }
                }
            }
        }
    }

    private suspend fun persist(s: LessonScript, finished: Boolean) {
        runCatching {
            planDao.upsert(
                LessonPlanStateEntity(
                    planId = s.planId,
                    clusterId = s.clusterId,
                    scriptJson = json.encodeToString(s),
                    cursor = s.cursor,
                    isFinished = finished,
                    startedAt = s.createdAt,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { logger.e("Director: persist failed: ${it.message}", it) }
    }

    private fun publish(s: LessonScript) {
        _state.value = DirectorState(
            active = true,
            planId = s.planId,
            clusterTitleRu = s.clusterTitleRu,
            cursor = s.cursor,
            totalSteps = s.totalSteps,
            currentStep = s.currentStep,
            steps = s.steps,
            flexUntilMs = flexFrozenUntil,
            newLemmas = s.newLemmas,
        )
    }

    private fun publishCurrent() {
        script?.let { publish(it) }
    }
}