package com.learnde.app.presentation.learn.v2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.learn.blind.BlindCommand
import com.learnde.app.learn.blind.BlindModeController
import com.learnde.app.learn.blind.BlindPhase
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreState
import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1GrammarDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.A1UserProgressDao
import com.learnde.app.learn.data.A1DataImporter
import com.learnde.app.learn.domain.A1SessionPlanner
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.Intervention
import com.learnde.app.learn.domain.v2.DirectorState
import com.learnde.app.learn.domain.v2.LessonDirector
import com.learnde.app.learn.domain.v2.LessonStep
import com.learnde.app.learn.domain.v2.StepKind
import com.learnde.app.learn.sessions.a1.A1LearningBus
import com.learnde.app.learn.sessions.a1.A1LearningEvent
import com.learnde.app.learn.sessions.a1.v2.A1AdaptiveSession
import com.learnde.app.learn.sessions.a1.v2.SessionControlAction
import com.learnde.app.learn.sessions.a1.v2.SessionControlBus
import com.learnde.app.learn.sessions.a1.v2.SystemTextBus
import com.learnde.app.presentation.learn.v2.components.FocusTone
import com.learnde.app.presentation.learn.v2.components.StepDot
import com.learnde.app.presentation.learn.v2.components.StepDotStatus
import com.learnde.app.presentation.learn.v2.components.WordFocus
import com.learnde.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StudioViewModel @Inject constructor(
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val importer: A1DataImporter,
    private val planner: A1SessionPlanner,
    private val adaptiveSession: A1AdaptiveSession,
    private val director: LessonDirector,
    private val bus: A1LearningBus,
    private val systemTextBus: SystemTextBus,
    private val controlBus: SessionControlBus,
    private val blind: BlindModeController,
    private val lemmaDao: A1LemmaDao,
    private val clusterDao: A1ClusterDao,
    private val grammarDao: A1GrammarDao,
    private val progressDao: A1UserProgressDao,
    private val logger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StudioEffect>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<StudioEffect> = _effects.asSharedFlow()

    /** Кэш последнего снапшота LearnCoreState (его толкает экран). */
    @Volatile private var lastConnection: LearnConnectionStatus = LearnConnectionStatus.Disconnected

    init {
        bootstrap()
        observeDirector()
        observeBus()
        observeBlind()
        observeSystemText()
        observeBlindCommands()
        observeCounters()
    }

    // ═════════════════════════════════════════════════════════════
    //  Bootstrap: импорт данных + первичный refresh
    // ═════════════════════════════════════════════════════════════

    private fun bootstrap() {
        viewModelScope.launch {
            runCatching {
                importer.importIfNeeded()
                if (clusterDao.getTotalCount() == 0) {
                    logger.w("Studio: БД пуста при актуальной версии — реимпорт")
                    importer.forceReimport()
                }
            }.onFailure {
                logger.e("Studio: import failed: ${it.message}", it)
                _state.update { s -> s.copy(loading = false, error = "Не удалось загрузить данные A1: ${it.message}") }
                return@launch
            }
            refresh()
        }
    }

    private suspend fun refresh() {
        val total = lemmaDao.getTotalCount()
        val mastered = lemmaDao.getMasteredCount()
        val inProgress = lemmaDao.getInProgressCount()
        val clustersTotal = clusterDao.getTotalCount()
        val clustersMastered = clusterDao.getMasteredCount()
        val due = (lemmaDao.getDueForReview(limit = 60).size)
        val progress = progressDao.get()

        _state.update {
            it.copy(
                loading = false,
                totalLemmas = total.coerceAtLeast(1),
                lemmasMastered = mastered,
                lemmasInProgress = inProgress,
                totalClusters = clustersTotal,
                clustersMastered = clustersMastered,
                dueForReview = due,
                streakDays = progress?.currentStreakDays ?: 0,
                a1Completed = progress?.isA1Completed ?: false,
            )
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Источник 1: DirectorState → позиция в уроке + карточка фокуса
    // ═════════════════════════════════════════════════════════════

    private fun observeDirector() {
        viewModelScope.launch {
            director.state.collect { d ->
                val dots = buildDots(d)
                val focus = buildFocus(d.currentStep)
                _state.update { s ->
                    s.copy(
                        sessionActive = d.active,
                        lessonTitle = d.clusterTitleRu,
                        cursor = d.cursor,
                        totalSteps = d.totalSteps,
                        currentStep = d.currentStep,
                        stepDots = dots,
                        isFlexNow = d.isFlexNow,
                        newLemmas = d.newLemmas,
                        focus = focus,
                    )
                }
                // Карточке слова нужен артикль — догружаем асинхронно.
                d.currentStep?.lemma?.let { enrichFocusArticle(it) }
            }
        }
    }

    private fun buildDots(d: DirectorState): List<StepDot> =
        d.steps.mapIndexed { i, step ->
            val status = when {
                i < d.cursor && step.status == com.learnde.app.learn.domain.v2.StepStatus.SKIPPED ->
                    StepDotStatus.SKIPPED
                i < d.cursor -> StepDotStatus.DONE
                i == d.cursor -> StepDotStatus.ACTIVE
                else -> StepDotStatus.PENDING
            }
            StepDot(index = i, status = status, label = step.kind.titleRu)
        }

    private fun buildFocus(step: LessonStep?): WordFocus? {
        if (step == null) return null
        val tone = when (step.kind) {
            StepKind.INTRODUCE, StepKind.ECHO -> FocusTone.NEW
            StepKind.RECALL_OLD, StepKind.RETRIEVE_NEW -> FocusTone.REVIEW
            StepKind.MICRO_DIALOG -> FocusTone.DIALOG
            StepKind.GRAMMAR_SPOT -> FocusTone.GRAMMAR
            StepKind.FINAL_RECALL, StepKind.WRAP_UP -> FocusTone.FINALE
            else -> FocusTone.NEUTRAL
        }
        // Лексические шаги — крупное слово; прочие — описание шага.
        val isLexical = step.lemma != null && step.kind in setOf(
            StepKind.INTRODUCE, StepKind.ECHO, StepKind.RETRIEVE_NEW,
            StepKind.RECALL_OLD, StepKind.USE_IN_CONTEXT,
        )
        return WordFocus(
            kindLabel = step.kind.titleRu,
            wordDe = if (isLexical) (step.lemma ?: "") else "",
            article = null, // догрузим в enrichFocusArticle
            wordRu = if (isLexical) step.lemmaRu else null,
            instruction = if (isLexical) null else humanInstruction(step),
            tone = tone,
        )
    }

    /** Короткое человекочитаемое описание не-лексического шага для карточки. */
    private fun humanInstruction(step: LessonStep): String = when (step.kind) {
        StepKind.GREETING -> "Лина здоровается и рассказывает план урока"
        StepKind.FLEX -> "Свободная минутка: поговорите о словах, придумайте ассоциации"
        StepKind.MICRO_DIALOG -> "Мини-диалог по ситуации урока"
        StepKind.GRAMMAR_SPOT -> "Короткое грамматическое правило"
        StepKind.FINAL_RECALL -> "Закрепление: вспоминаем все новые слова урока"
        StepKind.WRAP_UP -> "Подводим итог урока"
        else -> step.lemmaRu ?: "Шаг урока"
    }

    /** Догружает артикль для текущего лексического слова из БД. */
    private fun enrichFocusArticle(lemma: String) {
        viewModelScope.launch {
            val entity = runCatching { lemmaDao.getByLemma(lemma) }.getOrNull() ?: return@launch
            val article = entity.article
            _state.update { s ->
                val f = s.focus ?: return@update s
                if (f.wordDe == lemma && f.article != article) s.copy(focus = f.copy(article = article))
                else s
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Источник 2: A1LearningBus → оценки, грамматика, финал
    // ═════════════════════════════════════════════════════════════

    private fun observeBus() {
        viewModelScope.launch {
            bus.events.collect { event ->
                when (event) {
                    is A1LearningEvent.LemmaEvaluated -> _state.update {
                        it.copy(
                            lastEvaluation = StudioEvaluation(
                                lemma = event.lemma,
                                quality = event.quality,
                                diagnosis = event.diagnosis,
                                intervention = event.intervention,
                                feedback = event.feedback,
                            )
                        )
                    }

                    is A1LearningEvent.GrammarIntroduced -> _state.update {
                        it.copy(grammarIntroduced = event.ruleName)
                    }

                    is A1LearningEvent.SessionFinished -> {
                        _state.update {
                            it.copy(
                                sessionFinished = true,
                                sessionActive = false,
                                finalQuality = event.overallQuality,
                                finalFeedback = event.feedback,
                            )
                        }
                        // Обновляем глобальный прогресс после урока.
                        refresh()
                    }

                    // PhaseChanged / LemmaHeard / LemmaProduced — UI берёт из DirectorState.
                    else -> Unit
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Источник 3: BlindState
    // ═════════════════════════════════════════════════════════════

    private fun observeBlind() {
        viewModelScope.launch {
            blind.state.collect { b ->
                _state.update {
                    it.copy(
                        blindEnabled = b.enabled,
                        blindPhase = b.phase,
                        blindStatusLine = b.statusLine,
                        blindSessionsCompleted = b.sessionsCompleted,
                        blindMaxSessions = b.maxSessions,
                        blindBreakSecondsLeft = b.breakSecondsLeft,
                        a1Completed = it.a1Completed || b.a1Completed,
                    )
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Источник 4: системные тексты Director'а → в Gemini
    // ═════════════════════════════════════════════════════════════

    private fun observeSystemText() {
        viewModelScope.launch {
            systemTextBus.texts.collect { text ->
                _effects.tryEmit(StudioEffect.SendSystemText(text))
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Источник 5: команды Слепого режима → старт/стоп сессии
    // ═════════════════════════════════════════════════════════════

    private fun observeBlindCommands() {
        viewModelScope.launch {
            blind.commands.collect { cmd ->
                when (cmd) {
                    BlindCommand.StartSession -> _effects.tryEmit(StudioEffect.RequestStartSession)
                    BlindCommand.StopSession -> _effects.tryEmit(StudioEffect.RequestStopSession)
                    is BlindCommand.Notify -> _effects.tryEmit(StudioEffect.ShowToast(cmd.message))
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Реактивные счётчики (live-обновление прогресса)
    // ═════════════════════════════════════════════════════════════

    private fun observeCounters() {
        viewModelScope.launch {
            lemmaDao.observeMasteredCount().collect { c -> _state.update { it.copy(lemmasMastered = c) } }
        }
        viewModelScope.launch {
            lemmaDao.observeInProgressCount().collect { c -> _state.update { it.copy(lemmasInProgress = c) } }
        }
        viewModelScope.launch {
            clusterDao.observeMasteredCount().collect { c -> _state.update { it.copy(clustersMastered = c) } }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Снапшоты LearnCoreState (толкает экран через pushLearnCore)
    // ═════════════════════════════════════════════════════════════

    /**
     * Экран наблюдает общий LearnCoreViewModel и на каждое изменение
     * вызывает этот метод. Так StudioState всегда знает реальное
     * состояние соединения/аудио, не инжектя LearnCoreViewModel.
     */
    fun pushLearnCore(core: LearnCoreState) {
        val prevConnection = lastConnection
        lastConnection = core.connectionStatus

        _state.update {
            it.copy(
                connection = core.connectionStatus,
                isAiSpeaking = core.isAiSpeaking,
                isMicActive = core.isMicActive,
                isPreparing = core.isPreparingSession,
                isFinishing = core.isFinishingSession,
            )
        }

        // Сообщаем Слепому режиму о реальном статусе сессии.
        val nowRecording = core.connectionStatus == LearnConnectionStatus.Recording ||
            core.connectionStatus == LearnConnectionStatus.Ready
        val wasRecording = prevConnection == LearnConnectionStatus.Recording ||
            prevConnection == LearnConnectionStatus.Ready

        if (nowRecording && !wasRecording) {
            blind.onSessionConfirmedActive()
        }
        // Неожиданный обрыв при активном Слепом режиме (не штатный finish).
        if (!nowRecording && wasRecording &&
            core.connectionStatus == LearnConnectionStatus.Disconnected &&
            _state.value.blindEnabled &&
            _state.value.blindPhase == BlindPhase.RUNNING &&
            !_state.value.sessionFinished
        ) {
            blind.onSessionDroppedUnexpectedly()
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  INTENTS
    // ═════════════════════════════════════════════════════════════

    fun onIntent(intent: StudioIntent) {
        when (intent) {
            StudioIntent.StartLesson -> viewModelScope.launch { startLesson() }
            StudioIntent.StartReview -> viewModelScope.launch { startReview() }

            StudioIntent.StopSession -> {
                if (_state.value.blindEnabled) blind.disable(stopActiveSession = true)
                _effects.tryEmit(StudioEffect.RequestStopSession)
                _state.update { it.copy(sessionActive = false) }
            }

            StudioIntent.ToggleBlindMode -> toggleBlind()

            StudioIntent.SkipStep -> controlBus.emit(SessionControlAction.SKIP_STEP, byVoice = false)

            is StudioIntent.DisputeEvaluation -> viewModelScope.launch {
                _effects.tryEmit(
                    StudioEffect.SendSystemText(
                        "[СИСТЕМА]: Ученик не согласен с оценкой слова «${intent.lemma}». " +
                            "Считай ответ правильным, мягко согласись по-русски и продолжай урок."
                    )
                )
                _effects.tryEmit(StudioEffect.ShowToast("Оценка исправлена"))
                _state.update { s ->
                    val ev = s.lastEvaluation
                    if (ev != null && ev.lemma == intent.lemma) {
                        s.copy(
                            lastEvaluation = ev.copy(
                                quality = 7,
                                diagnosis = ErrorDiagnosis.None,
                                intervention = Intervention.PRAISE,
                            )
                        )
                    } else s
                }
            }

            StudioIntent.DismissFinalDialog -> {
                _state.update {
                    it.copy(sessionFinished = false, finalQuality = null, finalFeedback = null)
                }
                viewModelScope.launch { refresh() }
            }

            StudioIntent.AcknowledgeA1Completed -> _state.update { it.copy(a1Completed = false) }

            StudioIntent.DismissEvaluation -> _state.update { it.copy(lastEvaluation = null) }

            StudioIntent.Refresh -> viewModelScope.launch { refresh() }
        }
    }

    private suspend fun startLesson() {
        // Читаем clusterId, если перешли из истории или карты курса
        val targetClusterId = savedStateHandle.get<String>("clusterId")
        val cluster = if (!targetClusterId.isNullOrBlank()) {
            clusterDao.getById(targetClusterId) ?: planner.pickNextCluster()
        } else {
            planner.pickNextCluster()
        }

        if (cluster == null) {
            // Возможно, всё пройдено — но проверим, есть ли что повторить.
            val due = lemmaDao.getDueForReview(limit = 15)
            if (due.isEmpty()) {
                _effects.tryEmit(StudioEffect.ShowToast("Все кластеры A1 пройдены!"))
                _state.update { it.copy(a1Completed = true) }
            } else {
                startReview()
            }
            return
        }
        _state.update {
            it.copy(
                isReviewMode = false,
                sessionFinished = false,
                lastEvaluation = null,
                grammarIntroduced = null,
                finalQuality = null,
                finalFeedback = null,
            )
        }
        runCatching { adaptiveSession.prepareForCluster(cluster) }
            .onFailure {
                logger.e("Studio: prepareForCluster failed: ${it.message}", it)
                _effects.tryEmit(StudioEffect.ShowToast("Не удалось подготовить урок"))
                return
            }
        _effects.tryEmit(StudioEffect.RequestStartSession)
    }

    private suspend fun startReview() {
        val due = lemmaDao.getDueForReview(limit = 15)
        val weak = if (due.isEmpty()) lemmaDao.getWeakestLemmas(limit = 15) else emptyList()
        if (due.isEmpty() && weak.isEmpty()) {
            _effects.tryEmit(StudioEffect.ShowToast("Нет слов для повторения — отличная работа!"))
            return
        }
        _state.update {
            it.copy(
                isReviewMode = true,
                sessionFinished = false,
                lastEvaluation = null,
                finalQuality = null,
                finalFeedback = null,
            )
        }
        runCatching { adaptiveSession.prepareForReview(limit = 15) }
            .onFailure {
                logger.e("Studio: prepareForReview failed: ${it.message}", it)
                _effects.tryEmit(StudioEffect.ShowToast("Не удалось подготовить повторение"))
                return
            }
        _effects.tryEmit(StudioEffect.RequestStartSession)
    }

    private fun toggleBlind() {
        if (_state.value.blindEnabled) {
            blind.disable(stopActiveSession = true)
            _effects.tryEmit(StudioEffect.ShowToast("Слепой режим выключен"))
        } else {
            blind.enable()
            _effects.tryEmit(StudioEffect.ShowToast("Слепой режим включён — расслабьтесь и слушайте"))
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Слепой режим живёт в @Singleton-контроллере — НЕ выключаем его
        // здесь: пользователь мог свернуть экран, цепочка продолжается
        // под foreground-сервисом.
    }
}