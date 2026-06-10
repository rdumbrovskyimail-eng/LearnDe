package com.learnde.app.domain

import com.learnde.app.data.NetworkMonitor
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.learn.core.LearnScope
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Честный статус соединения для UI. */
enum class LinkState {
    IDLE,
    CONNECTING,
    READY,
    ROTATING,
    RECOVERING,
    FAILED,
}

@Singleton
class ConnectionOrchestrator @Inject constructor(
    @LearnScope private val liveClient: LiveClient,
    private val networkMonitor: NetworkMonitor,
    private val logger: AppLogger,
) {

    companion object {
        private const val STUCK_SOFT_MS = 12_000L
        private const val STUCK_HARD_MS = 25_000L
        private const val NETWORK_WAIT_MS = 30_000L
        private const val NUDGE_TEXT =
            "[СИСТЕМА]: Пауза затянулась. Продолжи урок: повтори вопрос короче или дай подсказку."
    }

    private val _state = MutableStateFlow(LinkState.IDLE)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _rotations = MutableStateFlow(0)
    val rotations: StateFlow<Int> = _rotations.asStateFlow()

    private val opMutex = Mutex()
    private var scope: CoroutineScope? = null

    private var apiKey: String = ""
    private var baseConfig: SessionConfig? = null
    private var maxAttempts: Int = 5
    private var baseDelayMs: Long = 2_000L
    private var maxDelayMs: Long = 30_000L

    private var stuckJob: Job? = null
    @Volatile private var lastUserTurnEndedAt = 0L
    @Volatile private var modelRespondedSinceUserTurn = true
    @Volatile private var nudgeSentForThisTurn = false

    var onPauseAudio: (suspend () -> Unit)? = null
    var onResumeAudio: (suspend () -> Unit)? = null
    var onPermanentFailure: ((String) -> Unit)? = null

    suspend fun start(
        scope: CoroutineScope,
        apiKey: String,
        config: SessionConfig,
        maxAttempts: Int,
        baseDelayMs: Long,
        maxDelayMs: Long,
    ) {
        this.scope = scope
        this.apiKey = apiKey
        this.baseConfig = config
        this.maxAttempts = maxAttempts
        this.baseDelayMs = baseDelayMs
        this.maxDelayMs = maxDelayMs
        _rotations.value = 0

        opMutex.withLock {
            transition(LinkState.CONNECTING, "start")
            liveClient.connect(apiKey, config)
        }
    }

    suspend fun stop() {
        stuckJob?.cancel(); stuckJob = null
        opMutex.withLock {
            transition(LinkState.IDLE, "stop")
            liveClient.disconnect()
        }
        scope = null
    }

    fun onEvent(event: GeminiEvent) {
        when (event) {
            is GeminiEvent.SetupComplete -> {
                transition(LinkState.READY, "setupComplete")
                scope?.launch { onResumeAudio?.invoke() }
            }

            is GeminiEvent.GoAway -> {
                logger.d("Link: GoAway(timeLeft=${event.timeLeft}) → planned rotation")
                scope?.launch { rotate(reason = "goAway") }
            }

            is GeminiEvent.ConnectionError -> {
                if (_state.value == LinkState.READY) {
                    scope?.launch { recover(reason = event.message) }
                }
            }

            is GeminiEvent.Disconnected -> {
                if (_state.value == LinkState.READY) {
                    scope?.launch { recover(reason = "ws closed ${event.code}") }
                }
            }

            else -> Unit
        }
    }

    fun onUserTurnEnded() {
        lastUserTurnEndedAt = System.currentTimeMillis()
        modelRespondedSinceUserTurn = false
        nudgeSentForThisTurn = false
        armStuckWatchdog()
    }

    fun onModelActivity() {
        modelRespondedSinceUserTurn = true
        stuckJob?.cancel()
    }

    private fun armStuckWatchdog() {
        stuckJob?.cancel()
        val s = scope ?: return
        stuckJob = s.launch {
            delay(STUCK_SOFT_MS)
            if (modelRespondedSinceUserTurn || _state.value != LinkState.READY) return@launch

            if (!nudgeSentForThisTurn) {
                nudgeSentForThisTurn = true
                logger.w("Link: model silent ${STUCK_SOFT_MS / 1000}s → soft nudge")
                runCatching { liveClient.sendRealtimeText(NUDGE_TEXT) }
            }

            delay(STUCK_HARD_MS - STUCK_SOFT_MS)
            if (modelRespondedSinceUserTurn || _state.value != LinkState.READY) return@launch

            logger.w("Link: model silent ${STUCK_HARD_MS / 1000}s → hard rotation")
            rotate(reason = "stuckTurn")
        }
    }

    private suspend fun rotate(reason: String) {
        val cfg = baseConfig ?: return
        if (!opMutex.tryLock()) return
        try {
            transition(LinkState.ROTATING, reason)
            onPauseAudio?.invoke()
            runCatching { liveClient.sendAudioStreamEnd() }

            val handle = liveClient.sessionHandle
            liveClient.disconnect()

            liveClient.connect(apiKey, cfg.copy(sessionHandle = handle))
            val ok = awaitReady(cfg.setupTimeoutMs + 2_000L)
            if (ok) {
                _rotations.value += 1
                logger.d("Link: rotation OK (#${_rotations.value}, handle=${handle != null})")
            } else {
                logger.e("Link: rotation failed → recover path")
                recoverLocked(reason = "rotationFailed")
            }
        } finally {
            opMutex.unlock()
        }
    }

    private suspend fun recover(reason: String) {
        if (!opMutex.tryLock()) return
        try { recoverLocked(reason) } finally { opMutex.unlock() }
    }

    private suspend fun recoverLocked(reason: String) {
        val cfg = baseConfig ?: return
        transition(LinkState.RECOVERING, reason)
        onPauseAudio?.invoke()
        runCatching { liveClient.disconnect() }

        for (attempt in 1..maxAttempts) {
            val online: Boolean = withTimeoutOrNull(NETWORK_WAIT_MS) {
                networkMonitor.isConnected.first { connected -> connected }
            } ?: false
            if (!online) {
                logger.e("Link: no network ${NETWORK_WAIT_MS / 1000}s — abort")
                break
            }

            val raw = (baseDelayMs * (1L shl (attempt - 1))).coerceAtMost(maxDelayMs)
            val jittered = (raw * (0.75 + Random.nextDouble() * 0.5)).toLong()
            logger.d("Link: recover attempt $attempt/$maxAttempts in ${jittered}ms")
            delay(jittered)

            val handle = if (attempt == 1) liveClient.sessionHandle else null
            runCatching { liveClient.connect(apiKey, cfg.copy(sessionHandle = handle)) }

            if (awaitReady(cfg.setupTimeoutMs + 2_000L)) {
                logger.d("Link: recovered on attempt $attempt (handle=${handle != null})")
                return
            }
            runCatching { liveClient.disconnect() }
        }

        transition(LinkState.FAILED, "attempts exhausted")
        onPermanentFailure?.invoke(
            "Связь потеряна. Проверьте интернет и нажмите «Продолжить» — прогресс сохранён."
        )
    }

    private suspend fun awaitReady(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!liveClient.isReady) delay(100)
            true
        } ?: false

    suspend fun retryAfterFailure() {
        if (_state.value != LinkState.FAILED) return
        recover(reason = "manualRetry")
    }

    private fun transition(to: LinkState, reason: String) {
        val from = _state.value
        if (from == to) return
        _state.value = to
        logger.d("Link: $from → $to ($reason)")
    }
}
