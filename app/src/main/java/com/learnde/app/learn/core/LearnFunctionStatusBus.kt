// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.2
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnFunctionStatusBus.kt
//
// ИЗМЕНЕНИЯ v3.2:
//   - Убран лишний Mutex — StateFlow.update атомарен (CAS)
//   - Убрано scope.launch на каждый вызов (dispatch delay устранён)
//   - Для pending используется ConcurrentHashMap.newKeySet (thread-safe)
//   - Fade-таймер запускается через scope.launch только там где нужен delay
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class FunctionPhase {
    IDLE,
    DETECTED,
    EXECUTING,
    COMPLETED,
}

data class FunctionStatus(
    val phase: FunctionPhase = FunctionPhase.IDLE,
    val functionName: String = "",
    val callId: String = "",
    val success: Boolean = true,
    val startedAtMs: Long = 0L,
    val finishedAtMs: Long = 0L,
    val concurrentCount: Int = 0,
    val tick: Long = 0L,
)

@Singleton
class LearnFunctionStatusBus @Inject constructor(
    private val logger: AppLogger
) {
    companion object {
        private const val FADE_TO_IDLE_MS = 1_200L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(FunctionStatus())
    val status: StateFlow<FunctionStatus> = _status.asStateFlow()

    // v3.2: thread-safe set без необходимости в Mutex
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val tickCounter = AtomicLong(0L)

    @Volatile private var fadeJob: Job? = null

    /** Детект toolCall в парсере WS — ДО запуска handler'а. */
    fun onDetected(functionName: String, callId: String) {
        pending.add(callId)
        val currentTick = tickCounter.incrementAndGet()
        val size = pending.size

        _status.update {
            it.copy(
                phase = FunctionPhase.DETECTED,
                functionName = functionName,
                callId = callId,
                success = true,
                startedAtMs = System.currentTimeMillis(),
                finishedAtMs = 0L,
                concurrentCount = size,
                tick = currentTick
            )
        }
        fadeJob?.cancel()
        logger.d("FnStatus: DETECTED $functionName (id=$callId, concurrent=$size)")
    }

    /** Handler начал выполнение. */
    fun onExecuting(functionName: String, callId: String) {
        val currentTick = tickCounter.incrementAndGet()
        val size = pending.size

        _status.update {
            it.copy(
                phase = FunctionPhase.EXECUTING,
                functionName = functionName,
                callId = callId,
                concurrentCount = size,
                tick = currentTick
            )
        }
        logger.d("FnStatus: EXECUTING $functionName")
    }

    /** Handler завершился. */
    fun onCompleted(functionName: String, callId: String, success: Boolean) {
        pending.remove(callId)
        val currentTick = tickCounter.incrementAndGet()
        val size = pending.size

        _status.update {
            it.copy(
                phase = FunctionPhase.COMPLETED,
                functionName = functionName,
                callId = callId,
                success = success,
                finishedAtMs = System.currentTimeMillis(),
                concurrentCount = size,
                tick = currentTick
            )
        }
        logger.d("FnStatus: COMPLETED $functionName (success=$success, remaining=$size)")

        // Fade в IDLE только если больше ничего не выполняется
        if (size == 0) {
            fadeJob?.cancel()
            fadeJob = scope.launch {
                delay(FADE_TO_IDLE_MS)
                if (pending.isEmpty()) {
                    val fadeTick = tickCounter.incrementAndGet()
                    _status.update {
                        it.copy(
                            phase = FunctionPhase.IDLE,
                            tick = fadeTick
                        )
                    }
                }
            }
        }
    }

    /** Очистка при exit из Learn. */
    fun reset() {
        fadeJob?.cancel()
        pending.clear()
        tickCounter.set(0L)
        _status.value = FunctionStatus()
    }
}