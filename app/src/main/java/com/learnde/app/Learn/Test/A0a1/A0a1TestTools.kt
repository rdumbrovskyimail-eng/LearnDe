// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/Learn/Test/A0a1/A0a1TestTools.kt
//
// ПЕРЕПИСАНО по паттерну TestFunctionTool (который работает):
//   • AwardPointsTool — ОДИН класс, который создаётся 4 раза (по разным баллам).
//     Не парсит args. Просто публикует PointsAwarded(points=N).
//   • FinishTestTool — без аргументов, просто публикует finish.
//
// Все 4 award-экземпляра регистрируются в ToolRegistry вместе с FinishTestTool.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.Learn.Test.A0a1

import com.learnde.app.domain.tools.ToolExecutor
import com.learnde.app.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единый класс для всех четырёх award_*_points функций.
 * Создаётся 4 раза в ToolRegistry через фабрику (см. правки ниже).
 */
class AwardPointsTool(
    private val points: Int,                  // 0..3, зашито в имени функции
    private val bus: A0a1TestBus,
    private val logger: AppLogger
) : ToolExecutor {

    override val name: String = when (points) {
        0 -> A0a1TestRegistry.FN_AWARD_0
        1 -> A0a1TestRegistry.FN_AWARD_1
        2 -> A0a1TestRegistry.FN_AWARD_2
        3 -> A0a1TestRegistry.FN_AWARD_3
        else -> error("Invalid points=$points")
    }

    override val description: String = "Award $points point(s) for current question"

    override suspend fun execute(args: Map<String, String>): String {
        logger.d("▶ $name (points=$points)")
        bus.publishAward(points)
        return """{"status":"ok","points":$points}"""
    }
}

@Singleton
class FinishTestTool @Inject constructor(
    private val bus: A0a1TestBus,
    private val logger: AppLogger
) : ToolExecutor {

    override val name: String = A0a1TestRegistry.FN_FINISH
    override val description: String = "Finish the A0-A1 test"

    override suspend fun execute(args: Map<String, String>): String {
        logger.d("▶ finish_test()")
        bus.publishFinish()
        return """{"status":"ok"}"""
    }
}

/* ════════════════════════════════════════════════════════════
   ИНТЕГРАЦИЯ В ToolRegistry.kt — финальный вариант:
   
   В конструктор добавить:
       private val a0a1Bus: com.learnde.app.Learn.Test.A0a1.A0a1TestBus,
       private val finishTool: com.learnde.app.Learn.Test.A0a1.FinishTestTool,
   
   В блоке executors:
       val base = listOf<ToolExecutor>(timeTool, deviceTool)
       val tests = FunctionsRegistry.ALL.map { TestFunctionTool(it, bus, logger) }
       val awards = (0..3).map {
           com.learnde.app.Learn.Test.A0a1.AwardPointsTool(it, a0a1Bus, logger)
       }
       (base + tests + awards + finishTool).associateBy { it.name }
   
   getFunctionDeclarationConfigs() возвращает ТОЛЬКО name+description — БЕЗ 
   parameters — как в оригинальном коде. Никаких правок не нужно.
   ════════════════════════════════════════════════════════════ */
