// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1LearningContract.kt
//
// MVI-контракт экрана обучения A1.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import com.learnde.app.learn.data.db.ClusterA1Entity

data class A1LearningState(
    val loading: Boolean = true,
    val error: String? = null,

    // ─── Прогресс ───
    val totalLemmas: Int = 835,
    val lemmasSeen: Int = 0,
    val lemmasMastered: Int = 0,
    val totalClusters: Int = 194,
    val clustersMastered: Int = 0,
    val grammarIntroduced: Int = 0,
    val grammarTotal: Int = 22,

    // ─── Текущая сессия ───
    val currentCluster: ClusterA1Entity? = null,
    val currentPhase: A1Phase = A1Phase.IDLE,
    val sessionActive: Boolean = false,
    val sessionFinished: Boolean = false,

    // ─── Live-данные во время сессии ───
    val lemmasHeardThisSession: Set<String> = emptySet(),
    val lemmasProducedThisSession: Set<String> = emptySet(),
    val lemmasFailedThisSession: Set<String> = emptySet(),
    val lastEvaluation: LastEvaluation? = null,
    val grammarIntroducedInSession: String? = null,

    // ─── Завершение сессии ───
    val finalQuality: Int? = null,
    val finalFeedback: String? = null,

    // ─── Достижение ───
    val isA1Completed: Boolean = false,
)

data class LastEvaluation(
    val lemma: String,
    val quality: Int,
    val wasCorrect: Boolean,
    val feedback: String,
)

sealed class A1LearningIntent {
    /** Загрузить/обновить прогресс и подобрать следующий кластер. */
    data object Refresh : A1LearningIntent()
    /** Начать сессию со следующим кластером из планировщика. */
    data object StartNextCluster : A1LearningIntent()
    /** Начать сессию с конкретным кластером (из карты тем). */
    data class StartCluster(val clusterId: String) : A1LearningIntent()
    /** Прервать текущую сессию. */
    data object StopSession : A1LearningIntent()
    /** Закрыть диалог итога сессии. */
    data object DismissFinalDialog : A1LearningIntent()
}

sealed class A1LearningEffect {
    /** Попросить LearnCore стартовать сессию с id = "a1_situation". */
    data object RequestStartSession : A1LearningEffect()
    /** Попросить LearnCore остановить текущую сессию. */
    data object RequestStopSession : A1LearningEffect()
    data class ShowToast(val msg: String) : A1LearningEffect()
}
