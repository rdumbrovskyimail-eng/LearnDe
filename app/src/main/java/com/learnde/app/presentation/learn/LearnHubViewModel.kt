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

    /**
     * Считает streak — количество последовательных дней с хотя бы одной сессией.
     * Сегодня учитывается только если уже была сессия.
     */
    private suspend fun recalcStreak() = withContext(Dispatchers.IO) {
        val all = sessionDao.getAllStartedTimestamps()  // List<Long>, отсортирован desc
        if (all.isEmpty()) {
            _state.update { it.copy(currentStreakDays = 0) }
            return@withContext
        }
        val days = all.map { ts ->
            val cal = Calendar.getInstance().apply {
                timeInMillis = ts
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }.toSortedSet().reversed().toList()

        if (days.isEmpty()) {
            _state.update { it.copy(currentStreakDays = 0) }
            return@withContext
        }

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val ONE_DAY = 24L * 3600L * 1000L
        var streak = 0
        var expected = today
        for (d in days) {
            when {
                d == expected -> {
                    streak++
                    expected -= ONE_DAY
                }
                d == expected + ONE_DAY -> {
                    // last session was yesterday — счётчик идёт от вчера
                    streak++
                    expected = d - ONE_DAY
                }
                d < expected -> {
                    // gap — стрик прервался
                    break
                }
                else -> { /* дубль одного и того же дня — пропускаем */ }
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
