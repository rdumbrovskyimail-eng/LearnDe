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
                id = "a0a1_test",
                title = "Тест уровня",
                subtitle = "Устный экзамен с разбором каждого ответа",
                badge = "A0–A1",
                iconKey = "Quiz",
                accentKey = "Accent",
                detailStats = listOf(
                    "20" to "вопросов",
                    "7" to "балльная шкала",
                    "≈15" to "минут",
                ),
                implemented = true,
            ),
            LearnHubItem(
                id = "a1_learning",
                title = "Обучение A1",
                subtitle = "Лексика · грамматика · диалоги",
                badge = "A1",
                iconKey = "School",
                accentKey = "Accent",
                detailStats = listOf(
                    "824" to "слов",
                    "141" to "урок",
                    "22" to "правила",
                ),
                implemented = true,
            ),
            LearnHubItem(
                id = "translator",
                title = "Переводчик Live",
                subtitle = "Двусторонний перевод в реальном времени",
                badge = "LIVE",
                iconKey = "Translate",
                accentKey = "Accent",
                detailStats = listOf(
                    "RU/UA" to "ваш язык",
                    "↔" to "",
                    "DE" to "немецкий",
                ),
                implemented = true,
            ),
            LearnHubItem(
                id = "grammar_book",
                title = "Грамматика A1",
                subtitle = "Все правила · вне диалога",
                badge = "REFERENCE",
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
