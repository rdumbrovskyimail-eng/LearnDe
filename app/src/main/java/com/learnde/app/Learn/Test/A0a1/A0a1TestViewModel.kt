// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/Learn/Test/A0a1/A0a1TestViewModel.kt
//
// ViewModel экрана теста A0-A1.
// Единственный источник правды по счёту и вердикту.
//
// Логика:
//   • При init публикуем startSignal — VoiceViewModel переведёт сессию в режим теста
//     и отправит модели "Начни тест.".
//   • Слушаем bus.awards — складываем баллы, защищаемся от дублей
//     (один вопрос = одна запись, берём максимум если модель переоценила).
//   • Слушаем bus.finished — ставим вердикт A0/A1.
//   • Если модель забудет вызвать finish_test, но уже ответила на 20 вопросов,
//     через 6 сек сами подводим итог (фолбэк).
//   • При выходе с экрана — публикуем exitSignal, VoiceViewModel вернёт обычный режим.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.Learn.Test.A0a1

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
    val currentQuestion: Int = 1,          // 1..20, показываем «Вопрос N / 20»
    val lastAward: PointsAwarded? = null,  // для короткой fly-in карточки
    val verdict: TestVerdict = TestVerdict.NONE,
    val finished: Boolean = false
) {
    val maxPoints: Int get() = A0a1TestRegistry.MAX_TOTAL_POINTS
    val threshold: Int get() = A0a1TestRegistry.A1_THRESHOLD
    val progress: Float get() = totalPoints.toFloat() / maxPoints.toFloat()
}

@HiltViewModel
class A0a1TestViewModel @Inject constructor(
    private val bus: A0a1TestBus
) : ViewModel() {

    private val _state = MutableStateFlow(A0a1TestUiState())
    val state: StateFlow<A0a1TestUiState> = _state.asStateFlow()

    /** Баллы по вопросам — чтобы не считать один вопрос дважды. */
    private val perQuestion = mutableMapOf<Int, Int>()

    private var autoFinishJob: Job? = null

    init {
        // 1) Сигналим VoiceViewModel: «открылся экран теста — переключайся в тест-режим».
        viewModelScope.launch { bus.publishStart() }

        // 2) Слушаем оценки.
        viewModelScope.launch {
            bus.awards.collect { award -> onAward(award) }
        }

        // 3) Слушаем завершение от модели.
        viewModelScope.launch {
            bus.finished.collect { finalizeVerdict() }
        }
    }

    private fun onAward(award: PointsAwarded) {
        // Защита от дублей/перезаписи: берём МАКСИМУМ между старым и новым.
        val prev = perQuestion[award.questionNumber] ?: -1
        val best = maxOf(prev, award.points)
        perQuestion[award.questionNumber] = best

        val total = perQuestion.values.sum()
        val answered = perQuestion.size
        val nextQ = (answered + 1).coerceAtMost(A0a1TestRegistry.TOTAL_QUESTIONS)

        _state.update {
            it.copy(
                totalPoints = total,
                answeredCount = answered,
                currentQuestion = nextQ,
                lastAward = award
            )
        }

        // Фолбэк: модель забыла вызвать finish_test — ждём 6 сек и сами подводим итог.
        if (answered >= A0a1TestRegistry.TOTAL_QUESTIONS && _state.value.verdict == TestVerdict.NONE) {
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
        val total = _state.value.totalPoints
        val verdict = if (total >= A0a1TestRegistry.A1_THRESHOLD) TestVerdict.A1 else TestVerdict.A0
        _state.update { it.copy(verdict = verdict, finished = true) }
    }

    /** Сброс прогресса — пользователь хочет пройти тест заново. */
    fun restart() {
        autoFinishJob?.cancel()
        perQuestion.clear()
        _state.value = A0a1TestUiState()
        viewModelScope.launch { bus.publishStart() }
    }

    /** Явный выход — например, при нажатии «Готово» или кнопки назад. */
    fun signalExit() {
        viewModelScope.launch { bus.publishExit() }
    }
}
