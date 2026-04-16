package com.codeextractor.app.domain.functions

import androidx.compose.ui.graphics.Color

/**
 * Единый реестр 10 тестовых функций для стриминга Gemini.
 *
 * Каждая функция имеет:
 *  - name      — имя для function calling (в snake_case, как требует API)
 *  - title     — короткая подпись для планшета в UI
 *  - description — что делает (отправляется в SessionConfig и показывается в UI)
 *  - colorIds  — набор индексов лампочек (0..9), которые загораются при её вызове
 *
 * Способ теста:
 *   1. Пользователь говорит "Выполни функцию 4".
 *   2. Gemini вызывает functionCall с name == "test_function_4".
 *   3. ToolRegistry.dispatch() → FunctionsRegistry.execute(4) → возвращает success,
 *      а UI через SharedFlow lightOn() зажигает лампочки из colorIds[4].
 */
object FunctionsRegistry {

    /** 10 отличимых ярких цветов лампочек (Material палитра) */
    val LIGHT_COLORS: List<Color> = listOf(
        Color(0xFFE53935), // 0 — красный
        Color(0xFFFB8C00), // 1 — оранжевый
        Color(0xFFFDD835), // 2 — жёлтый
        Color(0xFF7CB342), // 3 — лаймовый
        Color(0xFF43A047), // 4 — зелёный
        Color(0xFF00ACC1), // 5 — бирюзовый
        Color(0xFF1E88E5), // 6 — синий
        Color(0xFF5E35B1), // 7 — фиолетовый
        Color(0xFFD81B60), // 8 — малиновый
        Color(0xFFFFFFFF)  // 9 — белый
    )

    data class TestFunction(
        val number: Int,           // 1..10 — как говорит пользователь
        val name: String,          // snake_case для Gemini
        val title: String,         // короткое имя UI
        val description: String,   // полное описание для подсказки и для SessionConfig
        val colorIds: List<Int>    // индексы лампочек (0..9), которые загораются
    )

    /** 10 функций с уникальными подсветками. */
    val ALL: List<TestFunction> = listOf(
        TestFunction(1,  "test_function_1",  "Функция 1",
            "Приветствие и проверка связи. Загораются красная и синяя лампочки.",
            listOf(0, 6)),
        TestFunction(2,  "test_function_2",  "Функция 2",
            "Эмуляция проверки системы. Загораются оранжевая и зелёная.",
            listOf(1, 4)),
        TestFunction(3,  "test_function_3",  "Функция 3",
            "Диагностика сети. Загораются жёлтая, бирюзовая и белая.",
            listOf(2, 5, 9)),
        TestFunction(4,  "test_function_4",  "Функция 4",
            "Триадная подсветка — красный, зелёный, синий (RGB).",
            listOf(0, 4, 6)),
        TestFunction(5,  "test_function_5",  "Функция 5",
            "Тёплый дуэт — жёлтый и оранжевый.",
            listOf(1, 2)),
        TestFunction(6,  "test_function_6",  "Функция 6",
            "Холодная палитра — бирюзовый, синий, фиолетовый.",
            listOf(5, 6, 7)),
        TestFunction(7,  "test_function_7",  "Функция 7",
            "Радужная волна — все лампочки по кругу.",
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)),
        TestFunction(8,  "test_function_8",  "Функция 8",
            "Контрастная пара — малиновый и лаймовый.",
            listOf(8, 3)),
        TestFunction(9,  "test_function_9",  "Функция 9",
            "Одиночный импульс — только белая.",
            listOf(9)),
        TestFunction(10, "test_function_10", "Функция 10",
            "Финальный аккорд — фиолетовый, малиновый, красный.",
            listOf(7, 8, 0))
    )

    fun byName(name: String): TestFunction? = ALL.firstOrNull { it.name == name }
    fun byNumber(n: Int): TestFunction? = ALL.firstOrNull { it.number == n }
}