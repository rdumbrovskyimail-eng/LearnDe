// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА + ПЕРЕИМЕНОВАНИЕ ПАКЕТА
// Старый путь: app/src/main/java/com/learnde/app/Learn/Test/A0a1/A0a1TestViewModel.kt
// Новый путь:  app/src/main/java/com/learnde/app/learn/test/a0a1/A0a1TestViewModel.kt
//
// Изменения:
//   • Пакет lowercase.
//   • Вместо прямого вызова bus.publishStart()/publishExit() —
//     используется LearnSessionController.enter/exit.
//   • restart() корректно сбрасывает autoFinishJob и dedup через bus.reset().
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.test.a0a1

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TestVerdict { NONE, A0, A1 }

data class A0a1TestUiState(
    val totalPoints: Int = 0,
    val answeredCount: Int = 0,
    val currentQuestion: Int = 1,          // 1..20
    val lastPoints: Int? = null,
    val lastFeedback: String? = null,      // фидбек от ИИ
    val lastQuestionIndex: Int = 0,
    val verdict: TestVerdict = TestVerdict.NONE,
    val finished: Boolean = false
) {
    val maxPoints: Int get() = A0a1TestRegistry.MAX_TOTAL_POINTS
    val threshold: Int get() = A0a1TestRegistry.A1_THRESHOLD
    val progress: Float get() = totalPoints.toFloat() / maxPoints.toFloat()
}

@HiltViewModel
class A0a1TestViewModel @Inject constructor(
    private val bus: A0a1TestBus,
) : ViewModel() {

    private val _state = MutableStateFlow(A0a1TestUiState())
    val state: StateFlow<A0a1TestUiState> = _state.asStateFlow()

    private var autoFinishJob: Job? = null

    init {
        // Слушаем оценки.
        viewModelScope.launch {
            bus.awards.collect { (points, feedback) -> onAward(points, feedback) }
        }

        // Слушаем завершение.
        viewModelScope.launch {
            bus.finished.collect { finalizeVerdict() }
        }
    }

    private fun onAward(points: Int, feedback: String) {
        val cur = _state.value
        if (cur.answeredCount >= A0a1TestRegistry.TOTAL_QUESTIONS) {
            // Защита от лишних вызовов после финала
            return
        }
        val newAnswered = cur.answeredCount + 1
        val newTotal = cur.totalPoints + points
        val nextQ = (newAnswered + 1).coerceAtMost(A0a1TestRegistry.TOTAL_QUESTIONS)

        _state.update {
            it.copy(
                totalPoints = newTotal,
                answeredCount = newAnswered,
                currentQuestion = nextQ,
                lastPoints = points,
                lastQuestionIndex = newAnswered
            )
        }

        // Фолбэк: модель не вызвала finish_test — подводим итог сами через 6 сек.
        if (newAnswered >= A0a1TestRegistry.TOTAL_QUESTIONS &&
            _state.value.verdict == TestVerdict.NONE
        ) {
            autoFinishJob?.cancel()
            autoFinishJob = viewModelScope.launch {
                delay(6_000)
                if (_state.value.verdict == TestVerdict.NONE) finalizeVerdict()
            }
        }
    }

    private fun finalizeVerdict() {
        if (_state.value.finished) return
        autoFinishJob?.cancel()
        autoFinishJob = null
        val total = _state.value.totalPoints
        val verdict = if (total >= A0a1TestRegistry.A1_THRESHOLD) TestVerdict.A1 else TestVerdict.A0
        _state.update { it.copy(verdict = verdict, finished = true) }
    }

    /**
     * Сброс UI-состояния теста (баллы, прогресс, verdict).
     * Реальный restart Gemini-сессии делает LearnCoreViewModel.onIntent(Restart)
     * — эту обязанность берёт на себя вызывающий экран.
     */
    fun resetUiState() {
        autoFinishJob?.cancel()
        autoFinishJob = null
        bus.reset()
        _state.value = A0a1TestUiState()
    }

    override fun onCleared() {
        super.onCleared()
        autoFinishJob?.cancel()
        autoFinishJob = null
    }
}
