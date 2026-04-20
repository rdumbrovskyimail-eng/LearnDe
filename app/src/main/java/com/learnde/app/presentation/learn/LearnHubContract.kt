// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/presentation/learn/LearnHubContract.kt
//
// MVI-контракт главного экрана Learn-блока.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn

/**
 * Карточка теста/урока для списка в Hub.
 *
 * @param id          — id сессии в LearnSessionRegistry
 * @param title       — заголовок на карточке
 * @param subtitle    — подпись (длительность, уровень)
 * @param badge       — бейдж (например "A0-A1", "Neu")
 * @param implemented — реализован ли этот модуль (если нет — карточка
 *                     отображается серой с меткой «Скоро»)
 */
data class LearnHubItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val implemented: Boolean,
)

data class LearnHubState(
    val items: List<LearnHubItem> = DEFAULT_ITEMS,
    val apiKeySet: Boolean = false,
) {
    companion object {
        /**
         * Пока только один реальный модуль — A0-A1 тест.
         * Остальные карточки — плейсхолдеры для будущих уроков.
         */
        val DEFAULT_ITEMS: List<LearnHubItem> = listOf(
            LearnHubItem(
                id = "a0a1_test",
                title = "Тест A0 – A1",
                subtitle = "20 вопросов · устный экзамен",
                badge = "A0-A1",
                implemented = true,
            ),
            LearnHubItem(
                id = "a1_learning",
                title = "Обучение A1",
                subtitle = "194 урока · 835 слов · 22 правила",
                badge = "A1",
                implemented = true,
            ),
            LearnHubItem(
                id = "a2_vocab",
                title = "Словарь A2",
                subtitle = "Интервальное повторение · базовая лексика",
                badge = "A2",
                implemented = false,
            ),
            LearnHubItem(
                id = "grammar_akkusativ",
                title = "Грамматика: Akkusativ",
                subtitle = "Тренажёр склонения артиклей",
                badge = "A1+",
                implemented = false,
            ),
            LearnHubItem(
                id = "dialog_restaurant",
                title = "Диалог: Ресторан",
                subtitle = "Ролевая практика с аватаром-официантом",
                badge = "A2",
                implemented = false,
            ),
            LearnHubItem(
                id = "pron_umlauts",
                title = "Произношение: ä, ö, ü",
                subtitle = "Разбор с обратной связью по звуку",
                badge = "A1",
                implemented = false,
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