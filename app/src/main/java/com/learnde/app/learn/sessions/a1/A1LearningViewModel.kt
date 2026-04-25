// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.2
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1LearningViewModel.kt
//
// ИЗМЕНЕНИЯ v3.2:
//   - Поддержка StartReviewSession — подготавливает A1ReviewSession и стартует
//   - Расчёт weakLemmasCount (лемм с productionScore < 0.5)
//   - Флаг isReviewMode в state
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
    private val reviewSession: A1ReviewSession,    // v3.2: NEW
    private val bus: A1LearningBus,
    private val logger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(A1LearningState())
    val state: StateFlow<A1LearningState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<A1LearningEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<A1LearningEffect> = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            runCatching { importer.importIfNeeded() }
                .onFailure {
                    logger.e("A1ViewModel: import failed: ${it.message}", it)
                    _state.update { s -> s.copy(loading = false, error = "Не удалось загрузить данные A1: ${it.message}") }
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
            }
            is A1LearningIntent.DisputeEvaluation -> viewModelScope.launch {
                // Для review — пропускаем, у неё своя логика (TODO если понадобится)
                if (!_state.value.isReviewMode) {
                    session.disputeEvaluation(intent.lemma)
                }

                // ФИКС: Сообщаем Gemini о disputed evaluation.
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

        // v3.2: Посчитать weak lemmas для UI-бейджа "Повторить"
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
        _effects.tryEmit(A1LearningEffect.RequestStartSession)
    }

    /**
     * v3.2: Стартуем быструю review-сессию.
     */
    private suspend fun startReviewSession() {
        val weakCount = _state.value.weakLemmasCount
        if (weakCount == 0) {
            _effects.tryEmit(A1LearningEffect.ShowToast(
                "Нет слов для повторения — отличная работа!"
            ))
            return
        }

        // Подготавливаем леммы в самой сессии (вызывается до LearnCoreIntent.Start)
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

                    is A1LearningEvent.SessionFinished ->
                        _state.update { it.copy(
                            sessionFinished = true,
                            sessionActive = false,
                            finalQuality = event.overallQuality,
                            finalFeedback = event.feedback,
                        )}
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
}