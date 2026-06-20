// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0
// Путь: app/src/main/java/com/learnde/app/presentation/learn/LearnHubContract.kt
//
// ИЗМЕНЕНИЯ v5.0:
//   - Убраны эмодзи-флаги из detailStats переводчика
//   - 824 слова, 141 урок (синхронизировано с реальной БД)
//   - Подзаголовок теста теперь "Пройти заново · переоценка уровня" по умолчанию
//   - Бейдж теста меняется на REPLAY если уже пройден (controlled by ViewModel)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn

data class LearnHubItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val iconKey: String,
    val accentKey: String,                       // оставлено для совместимости, не используется в UI v5
    val detailStats: List<Pair<String, String>>,
    val implemented: Boolean,
)

data class LearnHubState(
    val items: List<LearnHubItem> = DEFAULT_ITEMS,
    val apiKeySet: Boolean = false,
    val currentStreakDays: Int = 0,
    val testWasPassed: Boolean = false,
) {
    companion object {
        val DEFAULT_ITEMS: List<LearnHubItem> = listOf(
            LearnHubItem(
                id = "a1_learning",
                title = "A1 · Адаптивный курс",
                subtitle = "Живые ситуации · интервальное повторение",
                badge = "A1",
                iconKey = "School",
                accentKey = "Accent",
                detailStats = emptyList(),
                implemented = true,
            ),
            LearnHubItem(
                id = "a1_book",
                title = "A1 · По учебнику",
                subtitle = "Schritte A1.1 · уроки, правила, диалоги",
                badge = "A1.1",
                iconKey = "Book",
                accentKey = "Accent",
                detailStats = emptyList(),
                implemented = true,
            ),
        )
    }
}

sealed class LearnHubIntent {
    data class OpenItem(val itemId: String) : LearnHubIntent()
    data object Back : LearnHubIntent()
}

sealed class LearnHubEffect {
    data class NavigateToItem(val route: String) : LearnHubEffect()
    data class ShowToast(val message: String) : LearnHubEffect()
}
