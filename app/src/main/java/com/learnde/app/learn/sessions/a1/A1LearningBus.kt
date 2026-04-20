// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1LearningBus.kt
//
// ИЗМЕНЕНИЯ:
//   - LemmaEvaluated теперь несёт ErrorDiagnosis и Intervention
//   - Старое поле wasCorrect оставлено (вычисляется из diagnosis.isError)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.Intervention
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class A1LearningEvent {
    data class PhaseChanged(val phase: A1Phase) : A1LearningEvent()
    data class LemmaHeard(val lemma: String) : A1LearningEvent()
    data class LemmaProduced(val lemma: String, val quality: Int) : A1LearningEvent()

    data class LemmaEvaluated(
        val lemma: String,
        val quality: Int,
        val diagnosis: ErrorDiagnosis,
        val intervention: Intervention,
        val feedback: String,
    ) : A1LearningEvent() {
        /** Для обратной совместимости с UI. */
        val wasCorrect: Boolean get() = !diagnosis.isError
    }

    data class GrammarIntroduced(val ruleId: String, val ruleName: String) : A1LearningEvent()
    data class SessionFinished(val overallQuality: Int, val feedback: String) : A1LearningEvent()
}

enum class A1Phase { IDLE, WARM_UP, INTRODUCE, DRILL, APPLY, GRAMMAR, COOL_DOWN, FINISHED }

@Singleton
class A1LearningBus @Inject constructor() {

    private val _events = MutableSharedFlow<A1LearningEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<A1LearningEvent> = _events.asSharedFlow()

    fun emit(event: A1LearningEvent) {
        _events.tryEmit(event)
    }
}