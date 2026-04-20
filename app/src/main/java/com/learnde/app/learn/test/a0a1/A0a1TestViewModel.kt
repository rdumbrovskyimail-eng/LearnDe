// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/test/a0a1/A0a1TestViewModel.kt
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

enum class TestPhase { A0, A1, A2, B1, B2 }
enum class TestVerdict { NONE, PASSED, FAILED }

data class A0a1TestUiState(
    val phase: TestPhase = TestPhase.A0,
    val totalPoints: Int = 0,
    val answeredCount: Int = 0,
    val currentQuestion: Int = 1,
    val lastPoints: Int? = null,
    val lastFeedback: String? = null,
    val lastQuestionIndex: Int = 0,
    val verdict: TestVerdict = TestVerdict.NONE,
    val finished: Boolean = false,

    // ─── Новые поля для премиального UI ───
    val currentQuestionText: String? = null,
    val lastAnswerCorrect: Boolean? = null,
    val lastAnswerReason: String? = null,
    val lastScoreRationale: String? = null,
) {
    val totalQuestions: Int get() = if (phase == TestPhase.A0) A0a1TestRegistry.A0_QUESTIONS else A0a1TestRegistry.STANDARD_QUESTIONS
    val maxPoints: Int get() = if (phase == TestPhase.A0) A0a1TestRegistry.A0_MAX_POINTS else A0a1TestRegistry.STANDARD_MAX_POINTS
    val threshold: Int get() = if (phase == TestPhase.A0) A0a1TestRegistry.A0_THRESHOLD else A0a1TestRegistry.STANDARD_THRESHOLD
    val isPassed: Boolean get() = totalPoints >= threshold
}

@HiltViewModel
class A0a1TestViewModel @Inject constructor(
    private val bus: A0a1TestBus,
) : ViewModel() {

    private val _state = MutableStateFlow(A0a1TestUiState())
    val state: StateFlow<A0a1TestUiState> = _state.asStateFlow()

    private var autoFinishJob: Job? = null

    init {
        viewModelScope.launch { bus.awards.collect { payload -> onAward(payload) } }
        viewModelScope.launch { bus.questions.collect { q -> onQuestion(q) } }
        viewModelScope.launch { bus.finished.collect { finalizeVerdict() } }
    }

    private fun onQuestion(payload: QuestionPayload) {
        _state.update {
            it.copy(
                currentQuestionText = payload.text,
                currentQuestion = payload.index.coerceAtLeast(1)
            )
        }
    }

    private fun onAward(payload: AwardPayload) {
        val cur = _state.value
        if (cur.answeredCount >= cur.totalQuestions) return

        val newAnswered = cur.answeredCount + 1
        val newTotal = cur.totalPoints + payload.points
        val nextQ = (newAnswered + 1).coerceAtMost(cur.totalQuestions)

        _state.update {
            it.copy(
                totalPoints = newTotal,
                answeredCount = newAnswered,
                currentQuestion = nextQ,
                lastPoints = payload.points,
                lastFeedback = payload.feedback,
                lastQuestionIndex = newAnswered,
                lastAnswerCorrect = payload.isCorrect,
                lastAnswerReason = payload.reason,
                lastScoreRationale = payload.scoreRationale,
            )
        }

        if (newAnswered >= cur.totalQuestions && _state.value.verdict == TestVerdict.NONE) {
            autoFinishJob?.cancel()
            autoFinishJob = viewModelScope.launch {
                delay(5_000)
                if (_state.value.verdict == TestVerdict.NONE) finalizeVerdict()
            }
        }
    }

    private fun finalizeVerdict() {
        if (_state.value.finished) return
        autoFinishJob?.cancel()
        autoFinishJob = null

        val passed = _state.value.isPassed
        val verdict = if (passed) TestVerdict.PASSED else TestVerdict.FAILED
        _state.update { it.copy(verdict = verdict, finished = true) }
    }

    fun advanceToNextPhase(): String? {
        autoFinishJob?.cancel()
        bus.reset()

        val nextPhase = when (_state.value.phase) {
            TestPhase.A0 -> TestPhase.A1
            TestPhase.A1 -> TestPhase.A2
            TestPhase.A2 -> TestPhase.B1
            TestPhase.B1 -> TestPhase.B2
            TestPhase.B2 -> null
        }

        if (nextPhase != null) {
            _state.value = A0a1TestUiState(phase = nextPhase)
            return "${nextPhase.name.lowercase()}_test"
        }
        return null
    }

    fun resetUiState() {
        autoFinishJob?.cancel()
        bus.reset()
        _state.value = A0a1TestUiState(phase = TestPhase.A0)
    }

    override fun onCleared() {
        super.onCleared()
        autoFinishJob?.cancel()
    }
}