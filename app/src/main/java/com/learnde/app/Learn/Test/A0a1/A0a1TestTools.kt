// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/Learn/Test/A0a1/A0a1TestTools.kt
//
// Два ToolExecutor'а для теста A0-A1:
//   • AwardPointsTool — парсит args {question_number, points, reason},
//                       валидирует, публикует PointsAwarded в A0a1TestBus.
//   • FinishTestTool  — публикует finish в A0a1TestBus.
//
// Оба реализуют интерфейс ToolExecutor из data/tools/ToolRegistry.kt.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.Learn.Test.A0a1

import com.learnde.app.domain.tools.ToolExecutor
import com.learnde.app.util.AppLogger
import javax.inject.Inject

class AwardPointsTool @Inject constructor(
    private val bus: A0a1TestBus,
    private val logger: AppLogger
) : ToolExecutor {

    override val name: String = A0a1TestRegistry.FN_AWARD
    override val description: String = A0a1TestRegistry.AWARD_DESCRIPTION

    override suspend fun execute(args: Map<String, String>): String {
        val qn = args["question_number"]?.toIntOrNull()
        val pts = args["points"]?.toIntOrNull()
        val reason = args["reason"].orEmpty()

        if (qn == null || qn !in 1..A0a1TestRegistry.TOTAL_QUESTIONS) {
            logger.w("award_points: invalid question_number=${args["question_number"]}")
            return """{"error":"question_number must be 1..${A0a1TestRegistry.TOTAL_QUESTIONS}"}"""
        }
        if (pts == null || pts !in 0..A0a1TestRegistry.MAX_POINTS_PER_QUESTION) {
            logger.w("award_points: invalid points=${args["points"]}")
            return """{"error":"points must be 0..${A0a1TestRegistry.MAX_POINTS_PER_QUESTION}"}"""
        }

        logger.d("▶ award_points(Q=$qn, pts=$pts, reason='$reason')")
        bus.publishAward(PointsAwarded(qn, pts, reason))
        return """{"status":"ok","question_number":$qn,"points":$pts}"""
    }
}

class FinishTestTool @Inject constructor(
    private val bus: A0a1TestBus,
    private val logger: AppLogger
) : ToolExecutor {

    override val name: String = A0a1TestRegistry.FN_FINISH
    override val description: String = A0a1TestRegistry.FINISH_DESCRIPTION

    override suspend fun execute(args: Map<String, String>): String {
        logger.d("▶ finish_test()")
        bus.publishFinish()
        return """{"status":"ok"}"""
    }
}
