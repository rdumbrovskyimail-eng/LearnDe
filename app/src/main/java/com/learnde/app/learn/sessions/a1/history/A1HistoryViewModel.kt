// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ (Patch 2.5)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/history/A1HistoryViewModel.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.A1SessionDao
import com.learnde.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
class A1HistoryViewModel @Inject constructor(
    private val sessionDao: A1SessionDao,
    private val clusterDao: A1ClusterDao,
    private val lemmaDao: A1LemmaDao,
    private val logger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(A1HistoryState())
    val state: StateFlow<A1HistoryState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<A1HistoryEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<A1HistoryEffect> = _effects.asSharedFlow()

    init {
        observeSessions()
        refreshStats()
    }

    fun onIntent(intent: A1HistoryIntent) {
        when (intent) {
            is A1HistoryIntent.ChangeFilter ->
                _state.update { it.copy(filter = intent.filter) }
            is A1HistoryIntent.RepeatCluster ->
                _effects.tryEmit(A1HistoryEffect.NavigateToCluster(intent.clusterId))
            is A1HistoryIntent.OpenDetails ->
                _effects.tryEmit(A1HistoryEffect.NavigateToDetails(intent.id))
            is A1HistoryIntent.DeleteSession -> viewModelScope.launch(Dispatchers.IO) {
                val session = sessionDao.getById(intent.id)
                sessionDao.deleteById(intent.id)
                if (session != null) {
                    val produced = runCatching {
                        kotlinx.serialization.json.Json.decodeFromString<List<String>>(session.lemmasProducedJson)
                    }.getOrDefault(emptyList())
                    val failed = runCatching {
                        kotlinx.serialization.json.Json.decodeFromString<List<String>>(session.lemmasFailedJson)
                    }.getOrDefault(emptyList())
                    produced.forEach { lemmaDao.decrementTimesProduced(it) }
                    failed.forEach { lemmaDao.decrementTimesFailed(it) }
                }
                _effects.tryEmit(A1HistoryEffect.ShowToast("Сессия удалена"))
            }
        }
    }

    private fun observeSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            sessionDao.observeAll().collect { entities ->
                // ОДИН запрос вместо N
                val clusterMap = clusterDao.getAll().associateBy { it.id }
                val items = entities.map { entity ->
                    val cluster = clusterMap[entity.clusterId]
                    SessionHistoryItem(
                        entity = entity,
                        clusterTitleRu = cluster?.titleRu ?: when (entity.clusterId) {
                            "review", "a1_review" -> "Повторение слабых слов"
                            else -> entity.clusterId
                        },
                        clusterTitleDe = cluster?.titleDe ?: "",
                    )
                }
                _state.update {
                    it.copy(items = items, loading = false, totalCount = items.size)
                }
            }
        }
    }

    private fun refreshStats() {
        viewModelScope.launch {
            val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            val weekCount = sessionDao.getCountSince(weekAgo)
            val avg = sessionDao.getAvgQualityRecent(10) ?: 0f
            _state.update {
                it.copy(thisWeekCount = weekCount, avgQualityRecent = avg)
            }
        }
    }

    fun filteredItems(): List<SessionHistoryItem> {
        val all = _state.value.items
        return when (_state.value.filter) {
            HistoryFilter.ALL -> all
            HistoryFilter.COMPLETE -> all.filter { it.entity.isComplete }
            HistoryFilter.INCOMPLETE -> all.filter { !it.entity.isComplete }
            HistoryFilter.THIS_WEEK -> {
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                all.filter { it.entity.startedAt >= weekAgo }
            }
        }
    }
}