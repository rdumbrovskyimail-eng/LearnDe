// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/Learn/Test/A0a1/A0a1TestRegistry.kt
//
// ПЕРЕПИСАНО по паттерну FunctionsRegistry (который реально работает):
//   • 5 функций БЕЗ параметров — только name и description
//   • Балл зашит в имени функции: award_0_points..award_3_points
//   • Номер вопроса Android считает сам (счётчик вызовов)
//   • finish_test — явное завершение
//
// Никаких parameters/required/types — ничего, на чём мог бы упасть 1007.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.Learn.Test.A0a1

import com.learnde.app.domain.model.FunctionDeclarationConfig

object A0a1TestRegistry {

    // ───── Константы теста ─────
    const val TOTAL_QUESTIONS = 20
    const val MAX_POINTS_PER_QUESTION = 3
    const val MAX_TOTAL_POINTS = TOTAL_QUESTIONS * MAX_POINTS_PER_QUESTION   // 60
    const val A1_THRESHOLD = 45

    // ───── Имена функций ─────
    const val FN_AWARD_0 = "award_0_points"
    const val FN_AWARD_1 = "award_1_point"
    const val FN_AWARD_2 = "award_2_points"
    const val FN_AWARD_3 = "award_3_points"
    const val FN_FINISH  = "finish_test"

    /** Все «баллы» функции — для удобства регистрации в ToolRegistry. */
    val AWARD_FUNCTIONS = listOf(FN_AWARD_0, FN_AWARD_1, FN_AWARD_2, FN_AWARD_3)

    /** Возвращает балл (0..3) по имени функции, или null если это не award-функция. */
    fun pointsForFunction(name: String): Int? = when (name) {
        FN_AWARD_0 -> 0
        FN_AWARD_1 -> 1
        FN_AWARD_2 -> 2
        FN_AWARD_3 -> 3
        else -> null
    }

    // ───── Декларации (для setup.tools.functionDeclarations) ─────
    val AWARD_0_DECLARATION = FunctionDeclarationConfig(
        name = FN_AWARD_0,
        description = "Вызови эту функцию если ответ пользователя пустой или не по теме. Балл: 0."
    )
    val AWARD_1_DECLARATION = FunctionDeclarationConfig(
        name = FN_AWARD_1,
        description = "Вызови эту функцию если пользователь назвал только отдельные слова без структуры предложения. Балл: 1."
    )
    val AWARD_2_DECLARATION = FunctionDeclarationConfig(
        name = FN_AWARD_2,
        description = "Вызови эту функцию если ответ понятен, но есть 1-2 ошибки в грамматике или произношении. Балл: 2."
    )
    val AWARD_3_DECLARATION = FunctionDeclarationConfig(
        name = FN_AWARD_3,
        description = "Вызови эту функцию если ответ полный, грамматически верный, с уверенным произношением. Балл: 3."
    )
    val FINISH_DECLARATION = FunctionDeclarationConfig(
        name = FN_FINISH,
        description = "Вызови эту функцию ТОЛЬКО после того, как пользователь ответил на 20-й (последний) вопрос и ты уже оценил его через award_*_points."
    )

    /** Все 5 деклараций для включения в тест-сессию. */
    val ALL_DECLARATIONS: List<FunctionDeclarationConfig> = listOf(
        AWARD_0_DECLARATION,
        AWARD_1_DECLARATION,
        AWARD_2_DECLARATION,
        AWARD_3_DECLARATION,
        FINISH_DECLARATION
    )

    // ───── Системная инструкция — полный сценарий для Gemini ─────
    val SYSTEM_INSTRUCTION = """
        Ты — дружелюбный преподаватель немецкого языка, проводящий устный тест на уровень A0-A1.
        
        СЦЕНАРИЙ:
        1. В САМОМ НАЧАЛЕ скажи по-русски: "Отлично, давай проведём тест!" и СРАЗУ задай первый вопрос.
        2. Всего 20 вопросов. Задавай их по порядку из раздела "ВОПРОСЫ" ниже.
        3. После КАЖДОГО ответа пользователя ты ОБЯЗАН:
           (а) дать короткий устный фидбек (1 предложение, по-русски),
           (б) ВЫЗВАТЬ ОДНУ из функций оценки:
               • award_3_points — ответ полный, грамматически верный, уверенное произношение;
               • award_2_points — ответ понятен, но 1-2 ошибки в грамматике/произношении;
               • award_1_point  — отдельные слова без структуры предложения;
               • award_0_points — нет ответа или не по теме;
           (в) задать следующий вопрос.
        4. После 20-го ответа: оцени его через award_*_points, затем вызови finish_test,
           затем коротко попрощайся.
        
        ВОПРОСЫ:
        ЧАСТЬ 1 — ОСНОВЫ
        1) (по-русски) Пожалуйста, назовите по буквам своё имя и фамилию на немецком.
        2) (по-немецки) Zählen Sie bitte von eins bis zwanzig.
        3) (по-русски) Скажите, какой сегодня день недели и число.
        
        ЧАСТЬ 2 — ОПИСАНИЕ И ЖИЛЬЁ
        4) (по-немецки) Wie sehen Sie aus? Beschreiben Sie sich bitte (Größe, Haare).
        5) (по-русски) Опишите вашу квартиру. Она большая или маленькая? Сколько там комнат?
        6) (по-немецки) Welche Kleidung tragen Sie heute? Was ist Ihre Lieblingsfarbe?
        
        ЧАСТЬ 3 — ЕДА, ТРАНСПОРТ, ВРЕМЯ
        7) (по-русски) Что вы обычно едите на обед в ресторане?
        8) (по-немецки) Wie kommen Sie zur Arbeit? Mit dem Auto, mit dem Bus oder mit der U-Bahn?
        9) (по-русски) В какое время вы ужинаете?
        
        ЧАСТЬ 4 — РАБОТА, ПОГОДА, САМОЧУВСТВИЕ
        10) (по-русски) Что вы делаете на работе?
        11) (по-немецки) Wie ist das Wetter heute bei Ihnen?
        12) (по-русски) Как вы себя чувствуете сегодня?
        
        ЧАСТЬ 5 — РОЛЕВЫЕ ИГРЫ И ПРОШЕДШЕЕ ВРЕМЯ
        13) (по-русски) Представьте, что вы в магазине. Попросите у продавца 1 килограмм яблок и спросите цену.
        14) (по-немецки) Sie sind am Bahnhof. Kaufen Sie eine Fahrkarte nach Berlin und fragen Sie nach der Abfahrt.
        15) (по-русски) Что вы делали вчера вечером?
        
        ЧАСТЬ 6 — СЕМЬЯ, ПЛАНЫ, ДОСУГ
        16) (по-немецки) Erzählen Sie bitte etwas über Ihre Familie.
        17) (по-русски) Что вы хотите делать завтра?
        18) (по-немецки) Haben Sie ein Haustier?
        19) (по-русски) Когда у вас день рождения?
        20) (по-немецки) Was ist Ihr Hobby?
        
        ВАЖНО:
        • Говори тёплым, ободряющим тоном — пользователь может волноваться.
        • На каждый ответ — ровно ОДИН вызов award_*_points. Без пропусков.
        • Не суммируй баллы вслух — это сделает приложение.
        • Никогда не объявляй итоговый уровень A0/A1 голосом.
        • После finish_test просто попрощайся в одну фразу.
    """.trimIndent()
}
