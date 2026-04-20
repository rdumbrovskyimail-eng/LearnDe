// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ (Patch 2.5)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/history/A1HistoryContract.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.history

import com.learnde.app.learn.data.db.A1SessionLogEntity

enum class HistoryFilter { ALL, COMPLETE, INCOMPLETE, THIS_WEEK }

data class SessionHistoryItem(
    val entity: A1SessionLogEntity,
    val clusterTitleRu: String,
    val clusterTitleDe: String,
)

data class A1HistoryState(
    val items: List<SessionHistoryItem> = emptyList(),
    val filter: HistoryFilter = HistoryFilter.ALL,
    val loading: Boolean = true,
    val totalCount: Int = 0,
    val avgQualityRecent: Float = 0f,
    val thisWeekCount: Int = 0,
)

sealed class A1HistoryIntent {
    data class ChangeFilter(val filter: HistoryFilter) : A1HistoryIntent()
    data class RepeatCluster(val clusterId: String) : A1HistoryIntent()
    data class DeleteSession(val id: Long) : A1HistoryIntent()
    data class OpenDetails(val id: Long) : A1HistoryIntent()
}

sealed class A1HistoryEffect {
    data class NavigateToCluster(val clusterId: String) : A1HistoryEffect()
    data class NavigateToDetails(val sessionId: Long) : A1HistoryEffect()
    data class ShowToast(val msg: String) : A1HistoryEffect()
}