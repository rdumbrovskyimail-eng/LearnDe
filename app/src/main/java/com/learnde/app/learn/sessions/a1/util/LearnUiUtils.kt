// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/util/LearnUiUtils.kt
//
// Единый источник правды для цветов оценок (1-7).
// Раньше дублировалось в A1HistoryScreen, SessionDetailsScreen, A1LearningScreen.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.util

import androidx.compose.ui.graphics.Color

object LearnUiUtils {
    fun qualityColor(quality: Int): Color = when (quality) {
        in 6..7 -> Color(0xFF43A047)  // зелёный
        in 4..5 -> Color(0xFFFB8C00)  // оранжевый
        in 2..3 -> Color(0xFFE53935)  // красный
        else -> Color(0xFF9E9E9E)     // серый (нет данных)
    }
    
    fun qualityLabel(quality: Int): String = when (quality) {
        7 -> "отлично"
        6 -> "хорошо"
        5 -> "норм"
        4 -> "слабо"
        3 -> "плохо"
        2 -> "очень плохо"
        1 -> "провал"
        else -> "—"
    }
    
    fun masteryColor(mastery: Float): Color = when {
        mastery >= 0.7f -> Color(0xFF43A047)
        mastery >= 0.3f -> Color(0xFFFB8C00)
        else -> Color(0xFFE53935)
    }
}