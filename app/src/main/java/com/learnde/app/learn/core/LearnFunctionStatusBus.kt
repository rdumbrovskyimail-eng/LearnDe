// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnFunctionStatusBus.kt
//
// Шина real-time статуса выполняемой функции Gemini.
// Используется для «инфо-табло» внизу каждого экрана Learn-блока.
//
// Жизненный цикл статуса:
//   IDLE
//    └─> DETECTED(name)    ← как только парсер нашёл toolCall в WS-фрейме
//          └─> EXECUTING(name)  ← handler начал работу
//                └─> COMPLETED(name, success)  ← handler вернул результат
//                      └─> IDLE (после FADE_TO_IDLE_MS)
//
// Для параллельных вызовов поддерживается очередь currentQueue:
// несколько функций одновременно → UI покажет все, но главной
// считается последняя DETECTED.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class FunctionPhase {
    IDLE,        // ничего не происходит
    DETECTED,    // toolCall пришёл, начинается обработка
    EXECUTING,   // handler запущен
    COMPLETED,   // handler завершился (+ success flag)
}

data class FunctionStatus(
    val phase: FunctionPhase = FunctionPhase.IDLE,
    val functionName: String = "",
    val callId: String = "",
    val success: Boolean = true,
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
    /** Сколько сейчас активных tool calls в очереди (для индикации параллелизма). */
    val concurrentCount: Int = 0,
    /** Монотонный счётчик вызовов — UI использует для ключа анимаций. */
    val tick: Long = 0L,
)

@Singleton
class LearnFunctionStatusBus @Inject constructor(
    private val logger: AppLogger
) {
    companion object {
        /** Через сколько после COMPLETED статус вернётся в IDLE (для плавного затухания). */
        private const val FADE_TO_IDLE_MS = 1_200L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    private val _status = MutableStateFlow(FunctionStatus())
    val status: StateFlow<FunctionStatus> = _status.asStateFlow()

    /** Сколько сейчас in-flight вызовов. */
    private val pending = mutableSetOf<String>()
    private var fadeJob: Job? = null
    private var tickCounter: Long = 0L

    /** Детект toolCall в парсере WS — ДО запуска handler'а. */
    fun onDetected(functionName: String, callId: String) {
        scope.launch {
            lock.withLock {
                pending.add(callId)
                tickCounter++
                _status.update {
                    it.copy(
                        phase = FunctionPhase.DETECTED,
                        functionName = functionName,
                        callId = callId,
                        success = true,
                        startedAtMs = System.currentTimeMillis(),
                        finishedAtMs = 0L,
                        concurrentCount = pending.size,
                        tick = tickCounter
                    )
                }
                fadeJob?.cancel()
                logger.d("FnStatus: DETECTED $functionName (id=$callId, concurrent=${pending.size})")
            }
        }
    }

    /** Handler начал выполнение. */
    fun onExecuting(functionName: String, callId: String) {
        scope.launch {
            lock.withLock {
                tickCounter++
                _status.update {
                    it.copy(
                        phase = FunctionPhase.EXECUTING,
                        functionName = functionName,
                        callId = callId,
                        concurrentCount = pending.size,
                        tick = tickCounter
                    )
                }
                logger.d("FnStatus: EXECUTING $functionName")
            }
        }
    }

    /** Handler завершился. */
    fun onCompleted(functionName: String, callId: String, success: Boolean) {
        scope.launch {
            lock.withLock {
                pending.remove(callId)
                tickCounter++
                _status.update {
                    it.copy(
                        phase = FunctionPhase.COMPLETED,
                        functionName = functionName,
                        callId = callId,
                        success = success,
                        finishedAtMs = System.currentTimeMillis(),
                        concurrentCount = pending.size,
                        tick = tickCounter
                    )
                }
                logger.d("FnStatus: COMPLETED $functionName (success=$success, remaining=${pending.size})")
                // Fade в IDLE только если больше ничего не выполняется
                if (pending.isEmpty()) {
                    fadeJob?.cancel()
                    fadeJob = scope.launch {
                        delay(FADE_TO_IDLE_MS)
                        lock.withLock {
                            if (pending.isEmpty()) {
                                tickCounter++
                                _status.update {
                                    it.copy(
                                        phase = FunctionPhase.IDLE,
                                        tick = tickCounter
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Очистка при exit из Learn. */
    fun reset() {
        scope.launch {
            lock.withLock {
                fadeJob?.cancel()
                pending.clear()
                tickCounter = 0L
                _status.value = FunctionStatus()
            }
        }
    }
}