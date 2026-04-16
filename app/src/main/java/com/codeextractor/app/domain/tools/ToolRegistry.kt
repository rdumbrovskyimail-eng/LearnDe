// ═══════════════════════════════════════════════════════════
// ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/domain/tools/ToolRegistry.kt
// Изменения: + getFunctionDeclarationConfigs() для SessionConfig
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.domain.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                       BatteryManager.BATTERY_STATUS_CHARGING

        return """{"battery_percent":$pct,"is_charging":$charging}"""
    }
}

@Singleton
class ToolRegistry @Inject constructor(
    private val timeTool: GetCurrentTimeTool,
    private val deviceTool: DeviceStatusTool,
    private val logger: AppLogger
) {
    private val executors: Map<String, ToolExecutor> by lazy {
        listOf(timeTool, deviceTool).associateBy { it.name }
    }

    fun getDeclarations(): List<ToolDeclaration> =
        executors.values.map { ToolDeclaration(it.name, it.description) }

    /**
     * Возвращает декларации в формате, пригодном для SessionConfig.
     * Используется в VoiceViewModel.buildSessionConfig().
     */
    fun getFunctionDeclarationConfigs(): List<FunctionDeclarationConfig> =
        executors.values.map { executor ->
            FunctionDeclarationConfig(
                name = executor.name,
                description = executor.description
            )
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

data class ToolDeclaration(val name: String, val description: String)