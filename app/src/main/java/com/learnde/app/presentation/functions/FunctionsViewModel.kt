package com.learnde.app.presentation.functions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.domain.functions.FunctionsEventBus
import com.learnde.app.domain.functions.FunctionsRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FunctionsState(
    val activeLightIds: Set<Int> = emptySet(),
    val statusText: String = "",
    val statusAlpha: Float = 0f,
    val lastExecutedNumber: Int? = null
)

@HiltViewModel
class FunctionsViewModel @Inject constructor(
    private val bus: FunctionsEventBus
) : ViewModel() {

    private val _state = MutableStateFlow(FunctionsState())
    val state: StateFlow<FunctionsState> = _state.asStateFlow()

    val functions: List<FunctionsRegistry.TestFunction> = FunctionsRegistry.ALL
    val palette = FunctionsRegistry.LIGHT_COLORS

    private var fadeJob: Job? = null

    init {
        viewModelScope.launch {
            bus.executed.collect { fn ->
                onFunctionExecuted(fn)
            }
        }
    }

    /** Может быть вызвано и из UI (локальный тест кнопкой). */
    fun onFunctionExecuted(fn: FunctionsRegistry.TestFunction) {
        fadeJob?.cancel()
        _state.update {
            it.copy(
                activeLightIds = fn.colorIds.toSet(),
                statusText = "Сейчас выполняется: ${fn.title} — ${fn.description}",
                statusAlpha = 1f,
                lastExecutedNumber = fn.number
            )
        }
        fadeJob = viewModelScope.launch {
            delay(2000)
            val steps = 20
            val totalMs = 1500L
            for (i in 1..steps) {
                delay(totalMs / steps)
                _state.update { it.copy(statusAlpha = 1f - i.toFloat() / steps) }
            }
            _state.update {
                it.copy(
                    activeLightIds = emptySet(),
                    statusText = "",
                    statusAlpha = 0f
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        fadeJob?.cancel()
    }
}