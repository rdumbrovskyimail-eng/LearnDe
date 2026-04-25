// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/presentation/learn/LearnHubViewModel.kt
//
// ViewModel главного экрана Learn-блока.
// Содержит список доступных тестов/уроков и реагирует на выбор пункта.
//
// ВАЖНО: этот VM НЕ владеет Gemini-сессией. Он только решает,
// на какой экран навигировать. Запуск сессии делает конкретный экран
// (например A0a1TestScreen) через LearnCoreViewModel.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LearnHubViewModel @Inject constructor(
    private val settingsStore: DataStore<AppSettings>,
    private val sessionDao: com.learnde.app.learn.data.db.A1SessionDao,
    private val logger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(LearnHubState())
    val state: StateFlow<LearnHubState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LearnHubEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<LearnHubEffect> = _effects.asSharedFlow()

    init {
        observeSettings()
        observeAdaptiveOrder()
    }

    private fun observeAdaptiveOrder() {
        viewModelScope.launch {
            sessionDao.observeTotal().collect { totalSessions ->
                if (totalSessions > 0) {
                    _state.update { s ->
                        val items = LearnHubState.DEFAULT_ITEMS.toMutableList()
                        val a1 = items.find { it.id == "a1_learning" }
                        val test = items.find { it.id == "a0a1_test" }
                        if (a1 != null && test != null) {
                            val translator = items.find { it.id == "translator" }
                            val updatedTest = test.copy(
                                subtitle = "Пройти заново · переоценка уровня",
                                badge = "REPLAY",
                            )
                            val newItems = listOfNotNull(a1, updatedTest, translator)
                            s.copy(items = newItems)
                        } else s
                    }
                }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.data
                .catch { e ->
                    logger.e("LearnHub: settings read error: ${e.message}")
                    emit(AppSettings())
                }
                .collect { s ->
                    _state.update { it.copy(apiKeySet = s.apiKey.isNotEmpty()) }
                }
        }
    }

    fun onIntent(intent: LearnHubIntent) {
        when (intent) {
            is LearnHubIntent.OpenItem -> handleOpenItem(intent.itemId)
            is LearnHubIntent.Back     -> { /* UI handles navigation */ }
        }
    }

    private fun handleOpenItem(itemId: String) {
        val item = _state.value.items.firstOrNull { it.id == itemId } ?: return
        if (!item.implemented) {
            _effects.tryEmit(LearnHubEffect.ShowToast("Модуль «${item.title}» скоро появится"))
            return
        }
        if (!_state.value.apiKeySet) {
            _effects.tryEmit(LearnHubEffect.ShowToast("Сначала задайте API-ключ в Настройках"))
            return
        }

        // Маршрут определяется по id — см. Routes в NavGraph
        val route = when (itemId) {
            "translator" -> "learn/translator"
            "a0a1_test" -> "learn/a0a1"
            "a1_learning" -> "learn/a1"
            else -> {
                logger.w("LearnHub: no route for itemId=$itemId")
                return
            }
        }
        _effects.tryEmit(LearnHubEffect.NavigateToItem(route))
    }
}