// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/domain/tools/ToolRegistry.kt
// Изменения:
//   + 10 тестовых функций test_function_1..10
//   + Публикация в FunctionsEventBus при выполнении
//   + getFunctionDeclarationConfigs() возвращает ВСЕ инструменты
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.domain.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.codeextractor.app.domain.functions.FunctionsEventBus
import com.codeextractor.app.domain.functions.FunctionsRegistry
import com.codeextractor.app.domain.model.FunctionCall
import com.codeextractor.app.domain.model.FunctionDeclarationConfig
import com.codeextractor.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface ToolExecutor {
    val name: String
    val description: String
    suspend fun execute(args: Map<String, String>): String
}

class GetCurrentTimeTool @Inject constructor() : ToolExecutor {
    override val name = "get_current_time"
    override val description = "Возвращает текущее время и дату пользователя"
    override suspend fun execute(args: Map<String, String>): String {
        val fmt = SimpleDateFormat("HH:mm:ss dd.MM.yyyy EEEE", Locale("ru"))
        return """{"time":"${fmt.format(Date())}","timezone":"${java.util.TimeZone.getDefault().id}"}"""
    }
}

class DeviceStatusTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolExecutor {
    override val name = "get_device_status"
    override val description = "Возвращает уровень заряда батареи и статус устройства"
    override suspend fun execute(args: Map<String, String>): String {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let {
            context.registerReceiver(null, it)
        }
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                BatteryManager.BATTERY_STATUS_CHARGING
        return """{"battery_percent":$pct,"is_charging":$charging}"""
    }
}

/**
 * Тестовая функция — при вызове публикует событие в FunctionsEventBus.
 * UI (FunctionsTestScreen) слушает bus и зажигает соответствующие лампочки.
 */
class TestFunctionTool(
    private val fn: FunctionsRegistry.TestFunction,
    private val bus: FunctionsEventBus,
    private val logger: AppLogger
) : ToolExecutor {
    override val name: String = fn.name
    override val description: String = fn.description
    override suspend fun execute(args: Map<String, String>): String {
        logger.d("▶ Test function executed: ${fn.name} (#${fn.number})")
        bus.publish(fn)
        return """{"status":"ok","function":${fn.number},"lights":${fn.colorIds.joinToString(",", "[", "]")}}"""
    }
}

@Singleton
class ToolRegistry @Inject constructor(
    private val timeTool: GetCurrentTimeTool,
    private val deviceTool: DeviceStatusTool,
    private val bus: FunctionsEventBus,
    private val logger: AppLogger
) {
    private val executors: Map<String, ToolExecutor> by lazy {
        val base = listOf<ToolExecutor>(timeTool, deviceTool)
        val tests = FunctionsRegistry.ALL.map { TestFunctionTool(it, bus, logger) }
        (base + tests).associateBy { it.name }
    }

    /** Для передачи в SessionConfig (setup.tools.functionDeclarations). */
    fun getFunctionDeclarationConfigs(): List<FunctionDeclarationConfig> =
        executors.values.map {
            FunctionDeclarationConfig(name = it.name, description = it.description)
        }

    suspend fun dispatch(call: FunctionCall): String {
        val executor = executors[call.name]
        if (executor == null) {
            logger.w("Unknown tool: ${call.name}")
            return """{"error":"Function '${call.name}' not implemented"}"""
        }
        return try {
            logger.d("Executing: ${call.name}(${call.args})")
            executor.execute(call.args)
        } catch (e: Exception) {
            logger.e("Tool execution failed: ${call.name}", e)
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }
}