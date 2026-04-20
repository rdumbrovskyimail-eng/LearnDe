// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a0a1/A0a1LearnSession.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a0a1

import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.FunctionDeclarationConfig
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.learn.test.a0a1.A0a1TestBus
import com.learnde.app.learn.test.a0a1.A0a1TestRegistry
import com.learnde.app.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

abstract class BaseLevelSession(
    private val bus: A0a1TestBus,
    private val logger: AppLogger
) : LearnSession {
    override val functionDeclarations: List<FunctionDeclarationConfig> = A0a1TestRegistry.ALL_DECLARATIONS
    override val initialUserMessage: String = "[СИСТЕМА]: Ученик готов. Начинай алгоритм."

    override suspend fun onEnter() {
        logger.d("▶ Session onEnter: $id")
        bus.reset()
    }

    override suspend fun onExit() {
        logger.d("◀ Session onExit: $id")
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        return when (call.name) {
            // ─── НОВАЯ ФУНКЦИЯ ДЛЯ ТЕКСТА ВОПРОСА ───
            A0a1TestRegistry.FN_ASK_QUESTION -> {
                val text = call.args["text"] ?: "Вопрос"
                val index = call.args["index"]?.toIntOrNull() ?: 1
                if (bus.tryConsume(call.id)) {
                    bus.publishQuestion(QuestionPayload(text, index))
                }
                """{"status":"ok"}"""
            }

            // ─── РАСШИРЕННАЯ ОЦЕНКА ───
            A0a1TestRegistry.FN_EVALUATE -> {
                val points = call.args["points"]?.toIntOrNull() ?: 0
                // JSON parser Gemini возвращает boolean как строки "true"/"false"
                val isCorrect = call.args["is_correct"]?.toBooleanStrictOrNull() ?: false
                val reason = call.args["reason"] ?: "Обоснование не предоставлено"
                val scoreRationale = call.args["score_rationale"] ?: "Балл выставлен на усмотрение ИИ"
                val feedback = call.args["feedback"] ?: "Оценено ИИ"

                if (bus.tryConsume(call.id)) {
                    val payload = AwardPayload(points, feedback, isCorrect, reason, scoreRationale)
                    bus.publishAward(payload)
                }
                """{"status":"ok", "recorded_points":$points}"""
            }

            A0a1TestRegistry.FN_FINISH -> {
                if (bus.tryConsume(call.id)) bus.publishFinish()
                """{"status":"ok"}"""
            }
            else -> """{"error":"function not available"}"""
        }
    }
}

@Singleton class A0LearnSession @Inject constructor(bus: A0a1TestBus, logger: AppLogger) : BaseLevelSession(bus, logger) {
    override val id: String = "a0_test"
    override val systemInstruction: String = A0a1TestRegistry.A0_SYSTEM_INSTRUCTION
}

@Singleton class A1LearnSession @Inject constructor(bus: A0a1TestBus, logger: AppLogger) : BaseLevelSession(bus, logger) {
    override val id: String = "a1_test"
    override val systemInstruction: String = A0a1TestRegistry.A1_SYSTEM_INSTRUCTION
}

@Singleton class A2LearnSession @Inject constructor(bus: A0a1TestBus, logger: AppLogger) : BaseLevelSession(bus, logger) {
    override val id: String = "a2_test"
    override val systemInstruction: String = A0a1TestRegistry.A2_SYSTEM_INSTRUCTION
}

@Singleton class B1LearnSession @Inject constructor(bus: A0a1TestBus, logger: AppLogger) : BaseLevelSession(bus, logger) {
    override val id: String = "b1_test"
    override val systemInstruction: String = A0a1TestRegistry.B1_SYSTEM_INSTRUCTION
}

@Singleton class B2LearnSession @Inject constructor(bus: A0a1TestBus, logger: AppLogger) : BaseLevelSession(bus, logger) {
    override val id: String = "b2_test"
    override val systemInstruction: String = A0a1TestRegistry.B2_SYSTEM_INSTRUCTION
}