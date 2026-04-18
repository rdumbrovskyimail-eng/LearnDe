// ═══════════════════════════════════════════════════════════
// ПЕРЕИМЕНОВАНИЕ ПАКЕТА
// Старый путь: app/src/main/java/com/learnde/app/Learn/Test/A0a1/A0a1TestRegistry.kt
// Новый путь:  app/src/main/java/com/learnde/app/learn/test/a0a1/A0a1TestRegistry.kt
//
// Содержимое без функциональных изменений. Только package lowercase.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.test.a0a1

import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.domain.model.ParameterConfig

object A0a1TestRegistry {

    // ───── Константы теста ─────
    const val TOTAL_QUESTIONS = 20
    const val MAX_POINTS_PER_QUESTION = 3
    const val MAX_TOTAL_POINTS = TOTAL_QUESTIONS * MAX_POINTS_PER_QUESTION   // 60
    const val A1_THRESHOLD = 45

    // ───── Имена функций ─────
    const val FN_EVALUATE = "evaluate_answer"
    const val FN_FINISH   = "finish_test"

    // ───── Декларации ─────
    val EVALUATE_DECLARATION = FunctionDeclarationConfig(
        name = FN_EVALUATE,
        description = "Вызови эту функцию после каждого ответа пользователя, чтобы оценить его.",
        parameters = mapOf(
            "points" to ParameterConfig(
                type = "INTEGER",
                description = "Балл от 0 до 3 (3 - отлично, 2 - с ошибками, 1 - слабо, 0 - неверно)."
            ),
            "feedback" to ParameterConfig(
                type = "STRING",
                description = "Краткий комментарий на русском: за что снижен балл или за что похвала."
            )
        ),
        required = listOf("points", "feedback")
    )

    val FINISH_DECLARATION = FunctionDeclarationConfig(
        name = FN_FINISH,
        description = "Вызови эту функцию ТОЛЬКО когда задал все 20 вопросов и оценил последний.",
        parameters = emptyMap(),
        required = emptyList()
    )

    val ALL_DECLARATIONS = listOf(EVALUATE_DECLARATION, FINISH_DECLARATION)

    // ───── Системная инструкция — полный сценарий для Gemini ─────
    val SYSTEM_INSTRUCTION = """
        Ты — дружелюбный преподаватель немецкого языка, проводящий устный тест на уровень A0-A1.
        
        НАЧАЛО:
        • Сразу после подключения, БЕЗ ожидания пользователя, скажи по-русски:
          "Отлично, давай проведём тест!" и задай первый вопрос.
        • НЕ жди от пользователя никакого приветствия — начинай сам.
        
        СЦЕНАРИЙ:
        1. В САМОМ НАЧАЛЕ скажи по-русски: "Отлично, давай проведём тест!" и СРАЗУ задай первый вопрос.
        2. Всего 20 вопросов. Задавай их по порядку из раздела "ВОПРОСЫ" ниже.
        3. После КАЖДОГО ответа пользователя ты ОБЯЗАН:
           (а) дать короткий устный фидбек (1 предложение, по-русски),
           (б) ВЫЗВАТЬ функцию evaluate_answer с параметрами points (0-3) и feedback (твой комментарий),
           (в) задать следующий вопрос.
        4. После 20-го ответа: оцени его через evaluate_answer, затем вызови finish_test,
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
        • На каждый ответ — ровно ОДИН вызов evaluate_answer. Без пропусков.
        • Не суммируй баллы вслух — это сделает приложение.
        • Никогда не объявляй итоговый уровень A0/A1 голосом.
        • После finish_test просто попрощайся в одну фразу.
    """.trimIndent()
}