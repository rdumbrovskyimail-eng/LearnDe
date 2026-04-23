// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.2
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1LearningContract.kt
//
// ИЗМЕНЕНИЯ v3.2:
//   - Новое состояние weakLemmasCount (сколько слов требует повтора сейчас)
//   - Новый интент StartReviewSession (быстрая сессия повторения)
//   - Новый эффект RequestStartReviewSession
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.Intervention

data class A1LearningState(
    val loading: Boolean = true,
    val error: String? = null,

    val totalLemmas: Int = 835,
    val lemmasSeen: Int = 0,
    val lemmasMastered: Int = 0,
    val lemmasInProgress: Int = 0,
    val totalClusters: Int = 194,
    val clustersMastered: Int = 0,
    val grammarIntroduced: Int = 0,
    val grammarTotal: Int = 22,

    // v3.2: Сколько слабых лемм сейчас "просят" повторения (для UI-бейджа)
    val weakLemmasCount: Int = 0,

    val currentCluster: ClusterA1Entity? = null,
    val currentPhase: A1Phase = A1Phase.IDLE,
    val sessionActive: Boolean = false,
    val sessionFinished: Boolean = false,
    val isReviewMode: Boolean = false,   // v3.2: активна ли review-сессия

    val lemmasHeardThisSession: Set<String> = emptySet(),
    val lemmasProducedThisSession: Set<String> = emptySet(),
    val lemmasFailedThisSession: Set<String> = emptySet(),
    val lastEvaluation: LastEvaluation? = null,
    val grammarIntroducedInSession: String? = null,

    val finalQuality: Int? = null,
    val finalFeedback: String? = null,

    val isA1Completed: Boolean = false,
)

data class LastEvaluation(
    val lemma: String,
    val quality: Int,
    val diagnosis: ErrorDiagnosis,
    val intervention: Intervention,
    val feedback: String,
) {
    val wasCorrect: Boolean get() = !diagnosis.isError
}

sealed class A1LearningIntent {
    data object Refresh : A1LearningIntent()
    data object StartNextCluster : A1LearningIntent()
    data class StartCluster(val clusterId: String) : A1LearningIntent()
    data object StartReviewSession : A1LearningIntent()    // v3.2: NEW
    data object StopSession : A1LearningIntent()
    data class DisputeEvaluation(val lemma: String) : A1LearningIntent()
    data object DismissFinalDialog : A1LearningIntent()
}

sealed class A1LearningEffect {
    data object RequestStartSession : A1LearningEffect()
    data object RequestStartReviewSession : A1LearningEffect()   // v3.2: NEW
    data object RequestStopSession : A1LearningEffect()
    data class ShowToast(val msg: String) : A1LearningEffect()
}