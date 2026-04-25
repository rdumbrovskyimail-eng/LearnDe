// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0
// Путь: app/src/main/java/com/learnde/app/presentation/learn/LearnHubViewModel.kt
//
// ИЗМЕНЕНИЯ v5.0:
//   - Считает streak (по дням с уроками)
//   - Понимает testWasPassed → меняет бейдж на REPLAY и подзаголовок
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.learn.data.db.A1SessionDao
import androidx.datastore.core.DataStore
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
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class LearnHubViewModel @Inject constructor(
    private val settingsStore: DataStore<AppSettings>,
    private val sessionDao: A1SessionDao,
) : ViewModel() {

    private val _state = MutableStateFlow(LearnHubState())
    val state: StateFlow<LearnHubState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LearnHubEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<LearnHubEffect> = _effects.asSharedFlow()

    init {
        observeSettings()
        viewModelScope.launch { recalcStreak() }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.data.collect { settings ->
                val passed = settings.testPassed
                val items = LearnHubState.DEFAULT_ITEMS.map {
                    if (it.id == "a0a1_test" && passed) it.copy(
                        badge = "REPLAY",
                        subtitle = "Пройти заново · переоценка уровня",
                    ) else it
                }
                _state.update {
                    it.copy(
                        apiKeySet = settings.apiKey.isNotEmpty(),
                        items = items,
                        testWasPassed = passed,
                    )
                }
            }
        }
    }

    private suspend fun recalcStreak() = withContext(Dispatchers.IO) {
        val all = sessionDao.getAllStartedTimestamps()
        if (all.isEmpty()) {
            _state.update { it.copy(currentStreakDays = 0) }
            return@withContext
        }
        val zone = java.time.ZoneId.systemDefault()
        val days = all.asSequence()
            .map { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .toSortedSet(reverseOrder())
            .toList()
        if (days.isEmpty()) {
            _state.update { it.copy(currentStreakDays = 0) }
            return@withContext
        }
        val today = java.time.LocalDate.now(zone)
        var streak = 0
        var expected = today
        for (d in days) {
            when {
                d == expected -> {
                    streak++
                    expected = expected.minusDays(1)
                }
                // Первая запись — вчера, сегодня ещё не учились — считаем от вчера.
                streak == 0 && d == today.minusDays(1) -> {
                    streak++
                    expected = d.minusDays(1)
                }
                d.isBefore(expected) -> break
                else -> { /* дубль того же дня — пропускаем */ }
            }
        }
        _state.update { it.copy(currentStreakDays = streak) }
    }

    fun onIntent(intent: LearnHubIntent) {
        when (intent) {
            is LearnHubIntent.OpenItem -> {
                val item = _state.value.items.firstOrNull { it.id == intent.itemId }
                if (item == null || !item.implemented) {
                    viewModelScope.launch {
                        _effects.emit(LearnHubEffect.ShowToast("Скоро будет доступно"))
                    }
                    return
                }
                if (!_state.value.apiKeySet) {
                    viewModelScope.launch {
                        _effects.emit(LearnHubEffect.ShowToast("Сначала задайте API-ключ в настройках"))
                    }
                    return
                }
                val route = when (intent.itemId) {
                    "a0a1_test" -> "learn/a0a1"
                    "a1_learning" -> "learn/a1"
                    "translator" -> "learn/translator"
                    "grammar_book" -> "learn/a1/grammar"
                    else -> return
                }
                viewModelScope.launch {
                    _effects.emit(LearnHubEffect.NavigateToItem(route))
                }
            }
            is LearnHubIntent.Back -> Unit
        }
    }
}
