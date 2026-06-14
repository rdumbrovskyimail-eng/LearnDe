package com.learnde.app.learn.blind

import com.learnde.app.learn.data.db.v2.LearnerProfileDao
import com.learnde.app.learn.data.db.v2.LearnerProfileEntity
import com.learnde.app.learn.domain.A1SessionPlanner
import com.learnde.app.learn.domain.v2.LessonDirector
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.learn.sessions.a1.A1LearningBus
import androidx.datastore.core.DataStore
import com.learnde.app.learn.sessions.a1.A1LearningEvent
import com.learnde.app.learn.sessions.a1.v2.A1AdaptiveSession
import com.learnde.app.learn.sessions.a1.v2.SessionControlAction
import com.learnde.app.learn.sessions.a1.v2.SessionControlBus
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

enum class BlindPhase {
    IDLE,
    RUNNING,
    BREAK,
    PAUSED,
    STARTING,
}

data class BlindState(
    val enabled: Boolean = false,
    val phase: BlindPhase = BlindPhase.IDLE,
    val sessionsCompleted: Int = 0,
    val maxSessions: Int = 8,
    val breakSecondsLeft: Int = 0,
    val nextIsReview: Boolean = false,
    val statusLine: String = "",
    val a1Completed: Boolean = false,
)

sealed class BlindCommand {
    data object StartSession : BlindCommand()
    data object StopSession : BlindCommand()
    data class Notify(val message: String) : BlindCommand()
}

@Singleton
class BlindModeController @Inject constructor(
    private val adaptiveSession: A1AdaptiveSession,
    private val planner: A1SessionPlanner,
    private val director: LessonDirector,
    private val bus: A1LearningBus,
    private val controlBus: SessionControlBus,
    private val profileDao: LearnerProfileDao,
    private val settingsStore: DataStore<AppSettings>,
    private val earcons: BlindEarcons,
    private val logger: AppLogger,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(BlindState())
    val state: StateFlow<BlindState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<BlindCommand>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commands: SharedFlow<BlindCommand> = _commands.asSharedFlow()

    @Volatile private var sessionConfirmedActive = false

    private var busJob: Job? = null
    private var controlJob: Job? = null
    private var breakJob: Job? = null
    private var watchdogJob: Job? = null

    private var startRetriesLeft = 1

    private companion object {
        const val START_TIMEOUT_MS = 45_000L
        const val POST_FINISH_GRACE_MS = 1_200L
    }

    fun enable() {
        scope.launch {
            mutex.withLock {
                if (_state.value.enabled) return@launch
                val profile = profileDao.get() ?: LearnerProfileEntity()
                _state.value = BlindState(
                    enabled = true,
                    phase = BlindPhase.STARTING,
                    sessionsCompleted = 0,
                    maxSessions = profile.blindMaxChainedSessions.coerceIn(1, 30),
                    statusLine = "Запускаю цепочку…",
                )
                subscribeBuses()
                logger.i("BlindMode: enabled (max=${_state.value.maxSessions})")
            }
            launchNextLesson(firstInChain = true)
        }
    }

    fun disable(stopActiveSession: Boolean = true) {
        scope.launch {
            mutex.withLock {
                if (!_state.value.enabled) return@launch
                cancelTimersLocked()
                unsubscribeBusesLocked()
                val wasRunning = _state.value.phase == BlindPhase.RUNNING ||
                    _state.value.phase == BlindPhase.STARTING
                _state.value = BlindState(enabled = false, phase = BlindPhase.IDLE)
                if (stopActiveSession && wasRunning) {
                    _commands.tryEmit(BlindCommand.StopSession)
                }
                earcons.release()
                logger.i("BlindMode: disabled")
            }
        }
    }

    fun onSessionConfirmedActive() {
        sessionConfirmedActive = true
        scope.launch {
            mutex.withLock {
                if (_state.value.enabled && _state.value.phase == BlindPhase.STARTING) {
                    _state.value = _state.value.copy(
                        phase = BlindPhase.RUNNING,
                        statusLine = lessonStatusLine(),
                    )
                }
            }
        }
    }

    fun onSessionDroppedUnexpectedly() {
        scope.launch {
            val st = _state.value
            if (!st.enabled || st.phase != BlindPhase.RUNNING) return@launch
            logger.w("BlindMode: session dropped unexpectedly — attempting resume")
            earcons.error()
            mutex.withLock {
                _state.value = st.copy(phase = BlindPhase.STARTING, statusLine = "Восстанавливаю урок…")
            }
            delay(2_000)
            adaptiveSession.prepareResume()
            requestStart()
        }
    }

    private fun subscribeBuses() {
        if (busJob == null) {
            busJob = scope.launch {
                bus.events.collect { event ->
                    if (event is A1LearningEvent.SessionFinished) onLessonFinished()
                }
            }
        }
        if (controlJob == null) {
            controlJob = scope.launch {
                controlBus.events.collect { e ->
                    if (!_state.value.enabled) return@collect
                    when (e.action) {
                        SessionControlAction.PAUSE -> pauseChain()
                        SessionControlAction.RESUME -> resumeChain()
                        SessionControlAction.STOP -> disable(stopActiveSession = true)
                        SessionControlAction.SKIP_BREAK -> skipBreak()
                        SessionControlAction.SKIP_STEP -> Unit
                    }
                }
            }
        }
    }

    private fun unsubscribeBusesLocked() {
        busJob?.cancel(); busJob = null
        controlJob?.cancel(); controlJob = null
    }

    private fun cancelTimersLocked() {
        breakJob?.cancel(); breakJob = null
        watchdogJob?.cancel(); watchdogJob = null
    }

    private fun onLessonFinished() {
        scope.launch {
            val st = _state.value
            if (!st.enabled) return@launch

            val completed = st.sessionsCompleted + 1
            profileDao.incrementBlindSessions()
            earcons.lessonFinished()
            logger.i("BlindMode: lesson finished ($completed/${st.maxSessions})")

            if (completed >= st.maxSessions) {
                mutex.withLock {
                    _state.value = st.copy(
                        sessionsCompleted = completed,
                        phase = BlindPhase.IDLE,
                        statusLine = "Цепочка завершена: $completed уроков. Отличная работа!",
                    )
                }
                delay(POST_FINISH_GRACE_MS)
                earcons.chainCompleted()
                disable(stopActiveSession = false)
                return@launch
            }

            mutex.withLock {
                _state.value = st.copy(sessionsCompleted = completed, phase = BlindPhase.BREAK)
            }
            delay(POST_FINISH_GRACE_MS)
            runBreakThenNext()
        }
    }

    private fun runBreakThenNext() {
        breakJob?.cancel()
        breakJob = scope.launch {
            val profile = profileDao.get() ?: LearnerProfileEntity()
            val total = profile.blindBreakSeconds.coerceIn(3, 120)
            for (left in total downTo 1) {
                val st = _state.value
                if (!st.enabled) return@launch
                if (st.phase == BlindPhase.PAUSED) return@launch
                _state.value = st.copy(
                    phase = BlindPhase.BREAK,
                    breakSecondsLeft = left,
                    statusLine = "Перерыв · следующий урок через $left с",
                )
                delay(1_000)
            }
            launchNextLesson(firstInChain = false)
        }
    }

    private fun skipBreak() {
        scope.launch {
            if (_state.value.phase != BlindPhase.BREAK) return@launch
            breakJob?.cancel(); breakJob = null
            logger.i("BlindMode: break skipped by user")
            launchNextLesson(firstInChain = false)
        }
    }

    private fun pauseChain() {
        scope.launch {
            mutex.withLock {
                val st = _state.value
                if (!st.enabled || st.phase == BlindPhase.PAUSED) return@launch
                cancelTimersLocked()
                val wasRunning = st.phase == BlindPhase.RUNNING || st.phase == BlindPhase.STARTING
                _state.value = st.copy(phase = BlindPhase.PAUSED, statusLine = "Пауза. Скажи «продолжи» или нажми ▶")
                if (wasRunning) {
                    _commands.tryEmit(BlindCommand.StopSession)
                }
            }
            earcons.paused()
        }
    }

    private fun resumeChain() {
        scope.launch {
            val st = _state.value
            if (!st.enabled || st.phase != BlindPhase.PAUSED) return@launch
            earcons.resumed()
            mutex.withLock {
                _state.value = st.copy(phase = BlindPhase.STARTING, statusLine = "Продолжаю…")
            }
            adaptiveSession.prepareResume()
            requestStart()
        }
    }

    private fun launchNextLesson(firstInChain: Boolean) {
        scope.launch {
            val st = _state.value
            if (!st.enabled) return@launch

            mutex.withLock {
                _state.value = _state.value.copy(
                    phase = BlindPhase.STARTING,
                    breakSecondsLeft = 0,
                    statusLine = "Готовлю урок…",
                )
            }

            val profile = profileDao.get() ?: LearnerProfileEntity()

            try {
                val heartbeat = settingsStore.data.first().sessionHeartbeatMs
                val recentlyKilled = heartbeat != 0L &&
                    (System.currentTimeMillis() - heartbeat) < 2 * 60_000L
                if (firstInChain && recentlyKilled && director.tryPeekActivePlanExists()) {
                    logger.i("BlindMode: resuming interrupted plan (crash recovery)")
                    adaptiveSession.prepareResume()
                    requestStart()
                    return@launch
                } else if (firstInChain) {
                    runCatching { director.discardActivePlan() }
                }

                val every = profile.blindReviewEvery.coerceIn(2, 10)
                val nextIndex = st.sessionsCompleted + 1
                val isReview = !firstInChain && nextIndex % every == 0

                if (isReview) {
                    logger.i("BlindMode: lesson #$nextIndex is REVIEW (every=$every)")
                    adaptiveSession.prepareForReview()
                    mutex.withLock { _state.value = _state.value.copy(nextIsReview = true) }
                    requestStart()
                    return@launch
                }

                val cluster = planner.pickNextCluster()
                if (cluster == null) {
                    val reviewLemmas = planner.pickReviewSessionLemmas(15)
                    if (reviewLemmas.isNotEmpty()) {
                        logger.i("BlindMode: no new clusters, falling back to review")
                        adaptiveSession.prepareForReview()
                        mutex.withLock { _state.value = _state.value.copy(nextIsReview = true) }
                        requestStart()
                    } else {
                        onA1Completed()
                    }
                    return@launch
                }

                logger.i("BlindMode: lesson #$nextIndex cluster=${cluster.id} «${cluster.titleRu}»")
                adaptiveSession.prepareForCluster(cluster)
                mutex.withLock { _state.value = _state.value.copy(nextIsReview = false) }
                requestStart()
            } catch (t: Throwable) {
                logger.e("BlindMode: failed to prepare next lesson: ${t.message}")
                earcons.error()
                if (startRetriesLeft-- > 0) {
                    delay(5_000)
                    launchNextLesson(firstInChain)
                } else {
                    disable(stopActiveSession = false)
                }
            }
        }
    }

    private fun requestStart() {
        sessionConfirmedActive = false
        startRetriesLeft = 1
        earcons.lessonStarting()
        _commands.tryEmit(BlindCommand.StartSession)
        armStartWatchdog()
    }

    private fun armStartWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            val ok = withTimeoutOrNull(START_TIMEOUT_MS) {
                while (!sessionConfirmedActive) delay(500)
                true
            } ?: false

            if (ok || !_state.value.enabled) return@launch

            if (startRetriesLeft-- > 0) {
                logger.w("BlindMode: start watchdog fired — retrying once")
                earcons.error()
                _commands.tryEmit(BlindCommand.StopSession)
                delay(3_000)
                adaptiveSession.prepareResume()
                sessionConfirmedActive = false
                earcons.lessonStarting()
                _commands.tryEmit(BlindCommand.StartSession)
                armStartWatchdog()
            } else {
                logger.e("BlindMode: start failed twice — stopping chain")
                earcons.error()
                disable(stopActiveSession = true)
            }
        }
    }

    private suspend fun onA1Completed() {
        logger.i("BlindMode: A1 COMPLETED — no clusters, no due reviews")
        mutex.withLock {
            _state.value = _state.value.copy(
                phase = BlindPhase.IDLE,
                a1Completed = true,
                statusLine = "А1 пройден полностью! 🎉",
            )
        }
        earcons.chainCompleted()
        disable(stopActiveSession = false)
    }

    private fun lessonStatusLine(): String {
        val st = _state.value
        val kind = if (st.nextIsReview) "Повторение" else "Урок"
        return "$kind ${st.sessionsCompleted + 1} из ${st.maxSessions} · идёт"
    }
}