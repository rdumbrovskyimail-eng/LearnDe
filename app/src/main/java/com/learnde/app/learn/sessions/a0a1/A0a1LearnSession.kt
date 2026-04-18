// ═══════════════════════════════════════════════════════════
// ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a0a1/A0a1LearnSession.kt
//
// Реализация LearnSession для теста A0-A1.
// Заменяет старые файлы A0a1TestTools.kt + часть логики VoiceViewModel.
//
// dedup: при повторе toolCall с тем же call.id (может произойти после
// reconnect) — повторный award не публикуется.
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

@Singleton
class A0a1LearnSession @Inject constructor(
    private val bus: A0a1TestBus,
    private val logger: AppLogger
) : LearnSession {

    override val id: String = "a0a1_test"

    override val systemInstruction: String = A0a1TestRegistry.SYSTEM_INSTRUCTION

    override val functionDeclarations: List<FunctionDeclarationConfig> =
        A0a1TestRegistry.ALL_DECLARATIONS

    // Скрытый системный пинг, чтобы ИИ начал тест сам (Требование Gemini 3.1)
    override val initialUserMessage: String = "[СИСТЕМА]: Ученик готов. Поздоровайся и задай первый вопрос из списка."

    override suspend fun onEnter() {
        logger.d("▶ A0a1LearnSession.onEnter")
        bus.reset()
    }

    override suspend fun onExit() {
        logger.d("◀ A0a1LearnSession.onExit")
        // История UI не чистится — оставляем экран как есть, если юзер вернётся
    }

    override suspend fun handleToolCall(call: FunctionCall): String? {
        return when (call.name) {
            A0a1TestRegistry.FN_EVALUATE -> {
                val points = call.args["points"]?.toIntOrNull() ?: 0
                val feedback = call.args["feedback"] ?: "Оценено ИИ"
                
                if (bus.tryConsume(call.id)) {
                    logger.d("▶ Оценка: $points баллов. Фидбек: $feedback")
                    bus.publishAward(points, feedback)
                } else {
                    logger.d("⚠ Duplicate evaluate id=${call.id} — ignored")
                }
                """{"status":"ok", "recorded_points":$points}"""
            }

            A0a1TestRegistry.FN_FINISH -> {
                if (bus.tryConsume(call.id)) {
                    logger.d("▶ finish_test (id=${call.id})")
                    bus.publishFinish()
                } else {
                    logger.d("⚠ Duplicate finish id=${call.id} — ignored")
                }
                """{"status":"ok"}"""
            }

            else -> {
                logger.w("A0a1: unknown function call: ${call.name}")
                """{"error":"function '${call.name}' is not available in a0a1_test mode"}"""
            }
        }
    }
}