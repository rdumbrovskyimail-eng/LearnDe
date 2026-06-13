package com.learnde.app.presentation.learn.v2

import com.learnde.app.learn.blind.BlindPhase
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.Intervention
import com.learnde.app.learn.domain.v2.LessonStep
import com.learnde.app.presentation.learn.v2.components.StepDot

// ─────────────────────────────────────────────────────────────────
//  STATE
// ─────────────────────────────────────────────────────────────────

data class StudioState(
    // ── Глобальный прогресс A1 ──
    val totalLemmas: Int = 824,
    val lemmasMastered: Int = 0,
    val lemmasInProgress: Int = 0,
    val totalClusters: Int = 141,
    val clustersMastered: Int = 0,
    val dueForReview: Int = 0,
    val streakDays: Int = 0,
    val a1Completed: Boolean = false,

    // ── Соединение / аудио ──
    val connection: LearnConnectionStatus = LearnConnectionStatus.Disconnected,
    val isAiSpeaking: Boolean = false,
    val isMicActive: Boolean = false,
    val isPreparing: Boolean = false,
    val isFinishing: Boolean = false,

    // ── Текущий урок (из DirectorState) ──
    val sessionActive: Boolean = false,
    val isReviewMode: Boolean = false,
    val lessonTitle: String = "",
    val cursor: Int = 0,
    val totalSteps: Int = 0,
    val currentStep: LessonStep? = null,
    val stepDots: List<StepDot> = emptyList(),
    val isFlexNow: Boolean = false,
    val newLemmas: List<String> = emptyList(),

    // ── Карточка фокуса (готовая проекция шага) ──
    val focus: com.learnde.app.presentation.learn.v2.components.WordFocus? = null,

    // ── Последняя оценка ──
    val lastEvaluation: StudioEvaluation? = null,

    // ── Грамматика, введённая в этой сессии ──
    val grammarIntroduced: String? = null,

    // ── Финал урока ──
    val sessionFinished: Boolean = false,
    val finalQuality: Int? = null,
    val finalFeedback: String? = null,

    // ── Слепой режим ──
    val blindEnabled: Boolean = false,
    val blindPhase: BlindPhase = BlindPhase.IDLE,
    val blindStatusLine: String = "",
    val blindSessionsCompleted: Int = 0,
    val blindMaxSessions: Int = 8,
    val blindBreakSecondsLeft: Int = 0,

    // ── Прочее ──
    val loading: Boolean = true,
    val error: String? = null,
) {
    val masteryFraction: Float
        get() = if (totalLemmas == 0) 0f
        else (lemmasMastered.toFloat() / totalLemmas).coerceIn(0f, 1f)

    val isConnecting: Boolean
        get() = connection == LearnConnectionStatus.Connecting ||
            connection == LearnConnectionStatus.Negotiating ||
            connection == LearnConnectionStatus.Reconnecting ||
            isPreparing

    val isConnected: Boolean
        get() = connection == LearnConnectionStatus.Ready ||
            connection == LearnConnectionStatus.Recording
}

data class StudioEvaluation(
    val lemma: String,
    val quality: Int,
    val diagnosis: ErrorDiagnosis,
    val intervention: Intervention,
    val feedback: String,
) {
    val wasCorrect: Boolean get() = !diagnosis.isError
}

// ─────────────────────────────────────────────────────────────────
//  INTENT
// ─────────────────────────────────────────────────────────────────

sealed class StudioIntent {
    /** Старт нового урока (следующий кластер по плану). */
    data object StartLesson : StudioIntent()

    /** Старт сессии повторения слабых слов. */
    data object StartReview : StudioIntent()

    /** Остановить текущую сессию. */
    data object StopSession : StudioIntent()

    /** Включить/выключить Слепой режим. */
    data object ToggleBlindMode : StudioIntent()

    /** Голос/кнопка: пропустить текущий шаг. */
    data object SkipStep : StudioIntent()

    /** Оспорить последнюю оценку слова. */
    data class DisputeEvaluation(val lemma: String) : StudioIntent()

    /** Закрыть финальный диалог урока. */
    data object DismissFinalDialog : StudioIntent()

    /** Подтвердить «А1 пройден». */
    data object AcknowledgeA1Completed : StudioIntent()

    /** Сбросить плашку последней оценки. */
    data object DismissEvaluation : StudioIntent()

    /** Обновить прогресс с БД. */
    data object Refresh : StudioIntent()
}

// ─────────────────────────────────────────────────────────────────
//  EFFECT
// ─────────────────────────────────────────────────────────────────

sealed class StudioEffect {
    /** Попросить экран запустить LearnCore-сессию "a1_adaptive". */
    data object RequestStartSession : StudioEffect()

    /** Попросить экран остановить LearnCore-сессию. */
    data object RequestStopSession : StudioEffect()

    /** Пробросить системный текст в Gemini (через LearnCore.sendSystemText). */
    data class SendSystemText(val text: String) : StudioEffect()

    /** Короткое уведомление. */
    data class ShowToast(val message: String) : StudioEffect()
}