// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА — v4
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1LearningViewModel.kt
//
// ИЗМЕНЕНИЯ v4:
//   [1] Интегрирован TutorHintEngine (Gemini 3.1 Flash Lite, второй контур):
//       стартует вместе с уроком, наполняет карточки-подсказки параллельно
//       голосовой модели, останавливается при завершении/выходе.
//       UI получает hintCards / hintUnread, бейдж сбрасывается markHintsRead().
//   [2] Страховка данных: если версия данных актуальна, а БД пуста
//       (прерванный импорт, очистка) — принудительный реимпорт.
//   [3] TutorHintEngine получает леммы кластера для контекста подсказок.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.learn.data.A1DataImporter
import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1GrammarDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.A1UserProgressDao
import com.learnde.app.learn.domain.A1SessionPlanner
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.Intervention
import com.learnde.app.learn.tutor.TutorHintCard
import com.learnde.app.learn.tutor.TutorHintEngine
import com.learnde.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class A1LearningViewModel @Inject constructor(
    private val importer: A1DataImporter,
    private val planner: A1SessionPlanner,
    private val lemmaDao: A1LemmaDao,
    private val clusterDao: A1ClusterDao,
    private val grammarDao: A1GrammarDao,
    private val progressDao: A1UserProgressDao,
    private val session: A1SituationSession,
    private val reviewSession: A1ReviewSession,
    private val bus: A1LearningBus,
    private val tutorHintEngine: TutorHintEngine,   // v4: NEW — контур подсказок
    private val logger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(A1LearningState())
    val state: StateFlow<A1LearningState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<A1LearningEffect>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val effects: SharedFlow<A1LearningEffect> = _effects.asSharedFlow()

    // ─── v4: Карточки второго контура (3.1 Flash Lite) — прямо в UI ───
    val hintCards: StateFlow<List<TutorHintCard>> = tutorHintEngine.cards
    val hintUnread: StateFlow<Int> = tutorHintEngine.unreadCount

    /** Панель раскрыта — бейдж непрочитанного сбрасывается. */
    fun markHintsRead() = tutorHintEngine.markAllRead()

    private val lemmasJson = Json { ignoreUnknownKeys = true }

    init {
        viewModelScope.launch {
            runCatching {
                importer.importIfNeeded()
                // [2] Страховка: флаг записан, а данных нет → реимпорт.
                if (clusterDao.getTotalCount() == 0) {
                    logger.w("A1ViewModel: БД пуста при актуальной версии — принудительный реимпорт")
                    importer.forceReimport()
                }
            }.onFailure {
                logger.e("A1ViewModel: import failed: ${it.message}", it)
                _state.update { s ->
                    s.copy(
                        loading = false,
                        error = "Не удалось загрузить данные A1: ${it.message}"
                    )
                }
                return@launch
            }
            refresh()
        }
        observeBus()
        observeCounters()
    }

    fun onIntent(intent: A1LearningIntent) {
        when (intent) {
            is A1LearningIntent.Refresh -> viewModelScope.launch { refresh() }
            is A1LearningIntent.StartNextCluster -> viewModelScope.launch { startNextCluster() }
            is A1LearningIntent.StartCluster -> viewModelScope.launch { startSpecificCluster(intent.clusterId) }
            is A1LearningIntent.StartReviewSession -> viewModelScope.launch { startReviewSession() }
            is A1LearningIntent.StopSession -> {
                _effects.tryEmit(A1LearningEffect.RequestStopSession)
                _state.update { it.copy(sessionActive = false, isReviewMode = false) }
                tutorHintEngine.stopAndClear()                       // v4
            }
            is A1LearningIntent.DisputeEvaluation -> viewModelScope.launch {
                if (!_state.value.isReviewMode) {
                    session.disputeEvaluation(intent.lemma)
                }
                _effects.tryEmit(A1LearningEffect.SendSystemTextToGemini(
                    "[СИСТЕМА]: Ученик оспорил твою оценку слова '${intent.lemma}'. " +
                    "Считай ответ правильным, кратко извинись по-русски и продолжай урок."
                ))
                _effects.tryEmit(A1LearningEffect.ShowToast("Оценка исправлена!"))
                _state.update { s ->
                    val ev = s.lastEvaluation
                    if (ev != null && ev.lemma == intent.lemma) {
                        s.copy(lastEvaluation = ev.copy(
                            quality = 7,
                            diagnosis = ErrorDiagnosis.None,
                            intervention = Intervention.PRAISE,
                        ))
                    } else s
                }
            }
            is A1LearningIntent.AcknowledgeSessionFinished -> {
                _state.update { it.copy(sessionFinished = false, finalQuality = null, finalFeedback = null) }
            }
            is A1LearningIntent.AcknowledgeA1Completed -> {
                _state.update { it.copy(isA1Completed = false) }
            }
            is A1LearningIntent.DismissFinalDialog -> {
                _state.update {
                    it.copy(
                        sessionFinished = false,
                        finalQuality = null,
                        finalFeedback = null,
                        isReviewMode = false,
                    )
                }
                viewModelScope.launch { refresh() }
            }
        }
    }

    private suspend fun refresh() {
        val lemmasTotal = lemmaDao.getTotalCount()
        val lemmasSeen = lemmaDao.getSeenCount()
        val lemmasMastered = lemmaDao.getMasteredCount()
        val lemmasInProgress = lemmaDao.getInProgressCount()
        val clustersTotal = clusterDao.getTotalCount()
        val clustersMastered = clusterDao.getMasteredCount()
        val next = planner.pickNextCluster()
        val userProgress = progressDao.get()

        val weakCount = lemmaDao.getWeakestLemmas(limit = 50).size +
                       lemmaDao.getDueForReview(limit = 50).size

        _state.update {
            it.copy(
                loading = false,
                totalLemmas = lemmasTotal,
                lemmasSeen = lemmasSeen,
                lemmasMastered = lemmasMastered,
                lemmasInProgress = lemmasInProgress,
                totalClusters = clustersTotal,
                clustersMastered = clustersMastered,
                currentCluster = next ?: it.currentCluster,
                isA1Completed = userProgress?.isA1Completed ?: false,
                weakLemmasCount = weakCount,
            )
        }
    }

    private suspend fun startNextCluster() {
        val next = planner.pickNextCluster()
        if (next == null) {
            _effects.tryEmit(A1LearningEffect.ShowToast("Все кластеры A1 пройдены!"))
            _state.update { it.copy(isA1Completed = true) }
            return
        }
        beginClusterSession(next.id)
    }

    private suspend fun startSpecificCluster(clusterId: String) {
        val cluster = clusterDao.getById(clusterId)
        if (cluster == null) {
            _effects.tryEmit(A1LearningEffect.ShowToast("Кластер не найден"))
            return
        }
        if (!cluster.isUnlocked) {
            _effects.tryEmit(A1LearningEffect.ShowToast("Этот кластер ещё не разблокирован"))
            return
        }
        beginClusterSession(clusterId)
    }

    private suspend fun beginClusterSession(clusterId: String) {
        val cluster = clusterDao.getById(clusterId) ?: return
        session.prepareForCluster(cluster)
        _state.update {
            it.copy(
                currentCluster = cluster,
                sessionActive = true,
                sessionFinished = false,
                isReviewMode = false,
                currentPhase = A1Phase.IDLE,
                lemmasHeardThisSession = emptySet(),
                lemmasProducedThisSession = emptySet(),
                lemmasFailedThisSession = emptySet(),
                lastEvaluation = null,
                grammarIntroducedInSession = null,
                finalQuality = null,
                finalFeedback = null,
            )
        }

        // v4 [1]+[3]: второй контур стартует синхронно с уроком,
        // зная тему и слова кластера.
        tutorHintEngine.start(
            scope = viewModelScope,
            topic = cluster.titleRu,
            lemmas = parseLemmas(cluster.lemmasJson),
        )

        _effects.tryEmit(A1LearningEffect.RequestStartSession)
    }

    private suspend fun startReviewSession() {
        val weakCount = _state.value.weakLemmasCount
        if (weakCount == 0) {
            _effects.tryEmit(A1LearningEffect.ShowToast(
                "Нет слов для повторения — отличная работа!"
            ))
            return
        }

        reviewSession.prepareLemmas(limit = 15)

        _state.update {
            it.copy(
                sessionActive = true,
                sessionFinished = false,
                isReviewMode = true,
                currentPhase = A1Phase.DRILL,
                lemmasHeardThisSession = emptySet(),
                lemmasProducedThisSession = emptySet(),
                lemmasFailedThisSession = emptySet(),
                lastEvaluation = null,
                finalQuality = null,
                finalFeedback = null,
            )
        }

        // v4: подсказки в режиме повторения — по слабым леммам.
        tutorHintEngine.start(
            scope = viewModelScope,
            topic = "Повторение слабых слов",
            lemmas = lemmaDao.getWeakestLemmas(limit = 15).map { it.lemma },
        )

        _effects.tryEmit(A1LearningEffect.RequestStartReviewSession)
    }

    private fun observeBus() {
        viewModelScope.launch {
            bus.events.collect { event ->
                when (event) {
                    is A1LearningEvent.PhaseChanged ->
                        _state.update { it.copy(currentPhase = event.phase) }

                    is A1LearningEvent.LemmaHeard ->
                        _state.update { it.copy(lemmasHeardThisSession = it.lemmasHeardThisSession + event.lemma) }

                    is A1LearningEvent.LemmaProduced ->
                        _state.update { it.copy(lemmasProducedThisSession = it.lemmasProducedThisSession + event.lemma) }

                    is A1LearningEvent.LemmaEvaluated -> {
                        _state.update { s ->
                            val wasCorrect = !event.diagnosis.isError
                            val newProduced = if (wasCorrect) s.lemmasProducedThisSession + event.lemma else s.lemmasProducedThisSession
                            val newFailed = if (!wasCorrect) s.lemmasFailedThisSession + event.lemma else s.lemmasFailedThisSession
                            s.copy(
                                lastEvaluation = LastEvaluation(
                                    lemma = event.lemma,
                                    quality = event.quality,
                                    diagnosis = event.diagnosis,
                                    intervention = event.intervention,
                                    feedback = event.feedback,
                                ),
                                lemmasProducedThisSession = newProduced,
                                lemmasFailedThisSession = newFailed,
                            )
                        }
                    }

                    is A1LearningEvent.GrammarIntroduced ->
                        _state.update { it.copy(grammarIntroducedInSession = event.ruleName) }

                    is A1LearningEvent.SessionFinished -> {
                        _state.update { it.copy(
                            sessionFinished = true,
                            sessionActive = false,
                            finalQuality = event.overallQuality,
                            finalFeedback = event.feedback,
                        )}
                        // v4: урок закончился — контур подсказок больше не нужен.
                        // Карточки оставляем видимыми до закрытия финального диалога?
                        // Нет: stopAndClear чистит список — ученик уже получил итог.
                        tutorHintEngine.stopAndClear()
                    }
                }
            }
        }
    }

    private fun observeCounters() {
        viewModelScope.launch {
            lemmaDao.observeMasteredCount().collect { count ->
                _state.update { it.copy(lemmasMastered = count) }
            }
        }
        viewModelScope.launch {
            lemmaDao.observeInProgressCount().collect { count ->
                _state.update { it.copy(lemmasInProgress = count) }
            }
        }
        viewModelScope.launch {
            lemmaDao.observeSeenCount().collect { count ->
                _state.update { it.copy(lemmasSeen = count) }
            }
        }
        viewModelScope.launch {
            clusterDao.observeMasteredCount().collect { count ->
                _state.update { it.copy(clustersMastered = count) }
            }
        }
        viewModelScope.launch {
            grammarDao.observeIntroducedCount().collect { count ->
                _state.update { it.copy(grammarIntroduced = count) }
            }
        }
    }

    private fun parseLemmas(json: String): List<String> =
        runCatching { lemmasJson.decodeFromString<List<String>>(json) }
            .getOrElse { emptyList() }

    private fun appendTranscript(current: String, chunk: String): String {
        if (chunk.isEmpty()) return current
        if (current.endsWith(chunk)) return current
        val maxOverlap = minOf(current.length, chunk.length)
        for (k in maxOverlap downTo 1) {
            if (current.regionMatches(current.length - k, chunk, 0, k))
                return current + chunk.substring(k)
        }
        return current + chunk
    }

    override fun onCleared() {
        super.onCleared()
        tutorHintEngine.stopAndClear()   // v4: не оставляем висящий контур
    }
}
