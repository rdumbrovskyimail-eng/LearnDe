// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v4.0
// Путь: app/src/main/java/com/learnde/app/presentation/learn/LearnHubContract.kt
//
// ИЗМЕНЕНИЯ v4.0:
//   - Оставлено только 3 модуля в правильном порядке:
//       1. Тест A0-A1
//       2. Обучение A1
//       3. Переводчик Live
//   - Удалены 4 заглушки (a2_vocab, grammar_akkusativ, dialog_restaurant, pron_umlauts)
//   - Карточки теперь имеют accentColor, iconKey и detailStats для premium-дизайна
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn

/**
 * Карточка модуля для главного экрана.
 *
 * @param id           — id сессии в LearnSessionRegistry
 * @param title        — заголовок
 * @param subtitle     — короткое описание
 * @param badge        — бейдж (уровень или "LIVE")
 * @param iconKey      — ключ иконки (Quiz / School / Translate)
 * @param accentKey    — ключ цвета (Blue / Green / Orange)
 * @param detailStats  — 3 пары (число, подпись) для live-статистики на карточке
 * @param implemented  — реализован ли модуль
 */
data class LearnHubItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val iconKey: String,
    val accentKey: String,
    val detailStats: List<Pair<String, String>>,
    val implemented: Boolean,
)

data class LearnHubState(
    val items: List<LearnHubItem> = DEFAULT_ITEMS,
    val apiKeySet: Boolean = false,
) {
    companion object {
        /**
         * Главные 3 модуля в пользовательском порядке:
         *   1. Тест (определить уровень)
         *   2. Обучение (прокачать)
         *   3. Переводчик (применить в жизни)
         */
        val DEFAULT_ITEMS: List<LearnHubItem> = listOf(
            LearnHubItem(
                id = "a0a1_test",
                title = "Тест A0 – A1",
                subtitle = "Устный экзамен с разбором каждого ответа",
                badge = "A0-A1",
                iconKey = "Quiz",
                accentKey = "Blue",
                detailStats = listOf(
                    "20" to "вопросов",
                    "7" to "балльная шкала",
                    "~15" to "минут",
                ),
                implemented = true,
            ),
            LearnHubItem(
                id = "a1_learning",
                title = "Обучение A1",
                subtitle = "Полный курс: лексика · грамматика · диалоги",
                badge = "A1",
                iconKey = "School",
                accentKey = "Green",
                detailStats = listOf(
                    "835" to "слов",
                    "194" to "урока",
                    "22" to "правила",
                ),
                implemented = true,
            ),
            LearnHubItem(
                id = "translator",
                title = "Переводчик Live",
                subtitle = "Живой двусторонний перевод в реальном времени",
                badge = "LIVE",
                iconKey = "Translate",
                accentKey = "Orange",
                detailStats = listOf(
                    "🇺🇦🇷🇺" to "ваш язык",
                    "↔" to "",
                    "🇩🇪" to "немецкий",
                ),
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
