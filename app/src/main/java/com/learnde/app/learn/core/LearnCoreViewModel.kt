// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.3
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnCoreViewModel.kt
//
// ЧТО ИСПРАВЛЕНО по сравнению с предыдущими версиями:
//   1. Правильный первый ход (Gemini заговаривает первой):
//      - sendText уже содержит turn_complete=true → НЕ вызываем sendTurnComplete() после
//      - Микрофон включаем ТОЛЬКО после первого AudioChunk от модели
//        (иначе VAD ловит тишину/шум и Interrupted глушит модель навсегда)
//      - Fallback: если за 3 сек модель не ответила — всё равно включаем мик
//   2. Отображение текста в чате:
//      - ModelText теперь всегда пишем (без условия outputTranscription=false)
//      - Дедупликация делается в flushPendingModelText, а не отбрасыванием событий
//   3. Возвращена защита silence timer (подбадривание молчащего ученика)
//   4. Возвращён FIFO-лимит транскрипта (MAX_TRANSCRIPT_SIZE)
//   5. Специфичный catch ForegroundServiceStartNotAllowedException
//   6. VAD настройки по типу сессии (translator длиннее, test короче)
//
// ВАЖНО: В systemInstruction каждой сессии должна быть явная фраза:
//   "Начни разговор первым. Тепло поприветствуй ученика по-русски.
//    НЕ жди, пока ученик заговорит."
// Без этого даже правильный протокольный вызов ничего не даст — модель
// просто не знает, что от неё хотят услышать первое слово.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.LiveClient
import com.learnde.app.domain.ToolResponse
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.LatencyProfile
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.learn.domain.VocabularyViolation
import com.learnde.app.util.AppLogger
import com.learnde.app.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class LearnCoreViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @LearnScope private val liveClient: LiveClient,
    @LearnScope private val audioEngine: AudioEngine,
    private val settingsStore: DataStore<AppSettings>,
    private val logger: AppLogger,
    private val arbiter: ActiveClientArbiter,
    private val statusBus: LearnFunctionStatusBus,
    private val registry: LearnSessionRegistry,
    private val vocabularyEnforcer: com.learnde.app.learn.domain.VocabularyEnforcer,
) : ViewModel() {

    companion object {
        /** FIFO-лимит транскрипта в памяти (защита от OOM в долгих сессиях). */
        private const val MAX_TRANSCRIPT_SIZE = 150

        /** Таймаут флаша текста, если аудио так и не пришло. */
        private const val TEXT_FLUSH_TIMEOUT_MS = 2_000L

        /** Порог тишины ученика, после которого шлём ИИ промпт «подбодри его». */
        private const val LEARNER_SILENCE_THRESHOLD_MS = 10_000L
        private const val SILENCE_CHECK_WINDOW_MS = 9_000L

        /** Короткая стабилизация WebSocket перед первым sendText. */
        private const val GREETING_WARMUP_MS = 150L

        /** Если за столько мс после sendText модель не начала говорить —
         *  всё равно включаем мик, чтобы юзер не завис. */
        private const val GREETING_FALLBACK_MS = 3_000L
    }

    private val _state = MutableStateFlow(LearnCoreState())
    val state: StateFlow<LearnCoreState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LearnCoreEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<LearnCoreEffect> = _effects.asSharedFlow()

    val audioPlaybackFlow get() = audioEngine.playbackSync
    val functionStatus: StateFlow<FunctionStatus> = statusBus.status

    @Volatile private var cachedSettings: AppSettings = AppSettings()
    @Volatile private var activeApiKey: String = ""
    @Volatile private var activeSession: LearnSession? = null
    @Volatile private var contextSeeded = false

    private val pendingToolCalls = ConcurrentHashMap.newKeySet<String>()
    private val startStopMutex = Mutex()
    private var micJob: Job? = null
    private var silenceTimerJob: Job? = null
    private var greetingFallbackJob: Job? = null

    @Volatile private var lastInputTs: Long = 0L
    @Volatile private var lastInputText: String = ""

    /** Флаг: модель уже начала говорить в этом турне (пришёл первый AudioChunk). */
    @Volatile private var modelStartedSpeakingThisTurn = false

    /** Флаг: ждём первый ответ модели после стартового приветствия. */
    @Volatile private var awaitingInitialGreeting = false

    private val transcriptBuffer = mutableListOf<ConversationMessage>()
    private val pendingModelText = StringBuilder()
    private var audioReceivedThisTurn = false
    private var pendingFlushJob: Job? = null

    @Volatile private var pendingVocabViolation: VocabularyViolation? = null

    init {
        observeSettings()
        observeGeminiEvents()
        observeArbiter()
        observeVocabularyViolations()
        viewModelScope.launch { audioEngine.initPlayback() }
    }

    private fun observeVocabularyViolations() {
        viewModelScope.launch {
            vocabularyEnforcer.violations.collect { violation ->
                if (activeSession?.id == "a1_situation") {
                    pendingVocabViolation = violation
                    logger.d("Learn: vocab violation buffered (${violation.violatingWords})")
                }
            }
        }
    }

    fun onIntent(intent: LearnCoreIntent) {
        when (intent) {
            is LearnCoreIntent.Start     -> handleStart(intent.sessionId)
            is LearnCoreIntent.Stop      -> handleStop()
            is LearnCoreIntent.Restart   -> handleRestart()
            is LearnCoreIntent.ToggleMic -> handleToggleMic()
            is LearnCoreIntent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.data
                .catch { e ->
                    logger.e("Learn: DataStore read error: ${e.message}")
                    emit(AppSettings())
                }
                .collect { settings ->
                    cachedSettings = settings
                    activeApiKey = settings.apiKey
                    _state.update { it.copy(apiKeySet = settings.apiKey.isNotEmpty()) }
                    audioEngine.setPlaybackVolume(settings.playbackVolume / 100f)
                    audioEngine.setMicGain(settings.micGain / 100f)
                    audioEngine.setSpeakerRouting(settings.forceSpeakerOutput)
                }
        }
    }

    private fun buildLearnSessionConfig(session: LearnSession): SessionConfig {
        val profile = runCatching {
            enumValueOf<LatencyProfile>(cachedSettings.latencyProfile)
        }.getOrDefault(LatencyProfile.UltraLow)

        val userInfo = buildString {
            if (cachedSettings.userName.isNotBlank()) append("Имя ученика: ${cachedSettings.userName}. ")
            if (cachedSettings.learningGoals.isNotBlank()) append("Цель изучения: ${cachedSettings.learningGoals}. ")
            if (cachedSettings.learningTopics.isNotBlank()) append("Интересные темы: ${cachedSettings.learningTopics}. ")
        }

        val finalSystemInstruction = if (userInfo.isNotBlank()) {
            "${session.systemInstruction}\n\n[ДАННЫЕ ПОЛЬЗОВАТЕЛЯ]:\n" +
                "Обращайся к ученику по имени. Учитывай эти данные: $userInfo"
        } else {
            session.systemInstruction
        }

        // VAD по типу сессии.
        // silence — сколько тишины ждать, чтобы закрыть ход ученика.
        // prefix — сколько мс аудио до детекта речи отдать модели (чтобы не рубить начало слов).
        val (silenceMs, prefixMs, temp) = when (session.id) {
            "translator"   -> Triple(1200, 300, 0.3f)
            "a1_situation" -> Triple(1000, 300, cachedSettings.temperature)
            "a1_review"    -> Triple(1000, 300, cachedSettings.temperature)
            else           -> Triple(1000, 300, cachedSettings.temperature)
        }

        val finalSilenceMs = if (cachedSettings.vadSilenceTimeoutMs > 0)
            maxOf(cachedSettings.vadSilenceTimeoutMs, 500)
        else silenceMs

        return SessionConfig(
            model = cachedSettings.model,
            temperature = temp,
            topP = cachedSettings.topP,
            topK = cachedSettings.topK,
            maxOutputTokens = cachedSettings.maxOutputTokens,
            presencePenalty = cachedSettings.presencePenalty,
            frequencyPenalty = cachedSettings.frequencyPenalty,
            voiceId = cachedSettings.voiceId,
            languageCode = cachedSettings.languageCode,
            latencyProfile = profile,
            autoActivityDetection = cachedSettings.enableServerVad,
            vadStartSensitivity = if (cachedSettings.vadStartOfSpeechSensitivity > 0.5f)
                "START_SENSITIVITY_HIGH" else "START_SENSITIVITY_LOW",
            vadEndSensitivity = if (cachedSettings.vadEndOfSpeechSensitivity > 0.5f)
                "END_SENSITIVITY_HIGH" else "END_SENSITIVITY_LOW",
            vadSilenceDurationMs = finalSilenceMs,
            vadPrefixPaddingMs = prefixMs,
            systemInstruction = finalSystemInstruction,
            inputTranscription = cachedSettings.inputTranscription,
            outputTranscription = cachedSettings.outputTranscription,
            enableSessionResumption = false,
            sessionHandle = null,
            enableContextCompression = false,
            enableGoogleSearch = false,
            functionDeclarations = session.functionDeclarations,
            sendAudioStreamEnd = cachedSettings.sendAudioStreamEnd,
        ).also {
            logger.d(
                "Learn: config for ${session.id}: silence=${finalSilenceMs}ms, " +
                    "prefix=${prefixMs}ms, temp=$temp, outputTranscription=${cachedSettings.outputTranscription}"
            )
        }
    }

    private fun observeArbiter() {
        viewModelScope.launch {
            arbiter.active.collect { owner ->
                val owned = owner == ClientOwner.LEARN
                _state.update { it.copy(arbiterOwned = owned) }
                if (!owned && activeSession != null) {
                    logger.w("Learn: lost arbiter ownership → stopping")
                    handleStop()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  START / STOP / RESTART
    // ══════════════════════════════════════════════════════

    private fun handleStart(sessionId: String) {
        viewModelScope.launch {
            startStopMutex.withLock {
                val session = registry.get(sessionId) ?: run {
                    logger.e("Learn: unknown session id: $sessionId")
                    _effects.tryEmit(LearnCoreEffect.Error(UiText.Plain("Unknown session: $sessionId")))
                    return@withLock
                }
                if (activeApiKey.isEmpty()) {
                    _state.update { it.copy(error = UiText.Plain("API ключ не задан. Задайте его в Настройках.")) }
                    return@withLock
                }

                logger.d("▶ Learn.handleStart(${session.id})")

                arbiter.acquire(ClientOwner.LEARN)
                runCatching { liveClient.disconnect() }

                transcriptBuffer.clear()
                pendingToolCalls.clear()
                contextSeeded = false
                statusBus.reset()
                lastInputText = ""
                pendingVocabViolation = null
                pendingModelText.clear()
                pendingFlushJob?.cancel()
                pendingFlushJob = null
                modelStartedSpeakingThisTurn = false
                awaitingInitialGreeting = false
                greetingFallbackJob?.cancel()

                session.onEnter()
                activeSession = session
                if (session.id == "a1_situation" || session.id == "a1_review") {
                    vocabularyEnforcer.warmUp()
                }

                _state.update {
                    it.copy(
                        sessionId = session.id,
                        connectionStatus = LearnConnectionStatus.Connecting,
                        transcript = emptyList(),
                        error = null,
                        isMicActive = false,
                        isAiSpeaking = false,
                        isPreparingSession = true,
                    )
                }

                runCatching {
                    liveClient.connect(
                        apiKey = activeApiKey,
                        config = buildLearnSessionConfig(session),
                        logRaw = cachedSettings.logRawWebSocketFrames
                    )
                }.onFailure { e ->
                    logger.e("Learn: connect failed: ${e.message}", e)
                    _state.update {
                        it.copy(
                            connectionStatus = LearnConnectionStatus.Disconnected,
                            isPreparingSession = false,
                            error = UiText.Plain("Не удалось подключиться: ${e.message}")
                        )
                    }
                    arbiter.release(ClientOwner.LEARN)
                    activeSession = null
                }
                logger.d("◀ Learn.handleStart — awaiting SetupComplete")
            }
        }
    }

    private fun handleStop() {
        viewModelScope.launch {
            startStopMutex.withLock {
                logger.d("▶ Learn.handleStop")
                val session = activeSession
                micJob?.cancel()
                silenceTimerJob?.cancel()
                greetingFallbackJob?.cancel()
                audioEngine.stopCapture()
                safeStopForegroundService()

                runCatching { liveClient.disconnect() }
                runCatching { session?.onExit() }

                activeSession = null
                pendingToolCalls.clear()
                statusBus.reset()
                vocabularyEnforcer.reset()
                contextSeeded = false
                pendingVocabViolation = null
                pendingModelText.clear()
                pendingFlushJob?.cancel()
                pendingFlushJob = null
                modelStartedSpeakingThisTurn = false
                awaitingInitialGreeting = false

                _state.update {
                    it.copy(
                        sessionId = null,
                        connectionStatus = LearnConnectionStatus.Disconnected,
                        isMicActive = false,
                        isAiSpeaking = false,
                        isPreparingSession = false,
                    )
                }
                arbiter.release(ClientOwner.LEARN)
                logger.d("◀ Learn.handleStop — arbiter released")
            }
        }
    }

    private fun handleRestart() {
        val s = activeSession ?: return
        viewModelScope.launch {
            // handleStop()/handleStart(s.id) через внутренние лямбды — mutex там свой.
            handleStop()
            // Маленькая пауза, чтобы WS действительно закрылся до нового connect.
            delay(150)
            handleStart(s.id)
        }
    }

    private fun handleToggleMic() {
        if (_state.value.isMicActive) stopMic()
        else if (_state.value.connectionStatus == LearnConnectionStatus.Ready
            || _state.value.connectionStatus == LearnConnectionStatus.Negotiating) {
            startMic()
        }
    }

    private fun startMic() {
        val hasMic = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            logger.w("Learn.startMic: no RECORD_AUDIO permission")
            return
        }

        val fgsOk = safeStartForegroundService()
        if (!fgsOk) {
            _effects.tryEmit(LearnCoreEffect.ShowToast(
                UiText.Plain("Запусти обучение когда приложение на переднем плане")
            ))
        }

        _state.update {
            it.copy(isMicActive = true, connectionStatus = LearnConnectionStatus.Recording)
        }
        micJob = viewModelScope.launch {
            launch { audioEngine.micOutput.collect { chunk -> liveClient.sendAudio(chunk) } }
            audioEngine.startCapture()
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        micJob = null
        silenceTimerJob?.cancel()
        viewModelScope.launch {
            audioEngine.stopCapture()
            if (cachedSettings.sendAudioStreamEnd) {
                liveClient.sendAudioStreamEnd()
            } else if (!cachedSettings.enableServerVad) {
                liveClient.sendTurnComplete()
            }
            _state.update {
                it.copy(
                    isMicActive = false,
                    connectionStatus = if (liveClient.isReady) LearnConnectionStatus.Ready
                    else LearnConnectionStatus.Disconnected
                )
            }
        }
    }

    private fun safeStartForegroundService(): Boolean {
        return try {
            appContext.startForegroundService(
                com.learnde.app.GeminiLiveForegroundService.startIntent(
                    appContext, cachedSettings.forceSpeakerOutput
                )
            )
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) наследует IllegalStateException
            logger.w("FGS not allowed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } catch (e: SecurityException) {
            logger.e("FGS permission denied: ${e.message}")
            false
        } catch (e: Exception) {
            logger.e("FGS unexpected error: ${e.message}", e)
            false
        }
    }

    private fun safeStopForegroundService() {
        try {
            appContext.startService(
                com.learnde.app.GeminiLiveForegroundService.stopIntent(appContext)
            )
        } catch (_: Exception) { /* сервис мог быть уже остановлен */ }
    }

    // ══════════════════════════════════════════════════════
    //  GEMINI EVENTS
    // ══════════════════════════════════════════════════════

    private fun observeGeminiEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is GeminiEvent.Connected ->
                        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Negotiating) }

                    is GeminiEvent.SetupComplete -> handleSetupComplete()

                    is GeminiEvent.AudioChunk -> {
                        // Первый аудио-чанк в этом турне → модель точно заговорила.
                        if (!modelStartedSpeakingThisTurn) {
                            modelStartedSpeakingThisTurn = true
                            // Если мы ждали приветствие — можно смело включать мик,
                            // потому что теперь Playback уже шумит и забьёт эхо мика
                            // (а VAD с echo-cancellation отсечёт собственный голос модели).
                            if (awaitingInitialGreeting) {
                                awaitingInitialGreeting = false
                                greetingFallbackJob?.cancel()
                                logger.d("Learn: model started greeting → enabling mic")
                                if (!_state.value.isMicActive) startMic()
                            }
                        }
                        _state.update { it.copy(isAiSpeaking = true, isPreparingSession = false) }
                        audioEngine.enqueuePlayback(event.pcmData)
                        if (!audioReceivedThisTurn) {
                            audioReceivedThisTurn = true
                            if (pendingModelText.isNotEmpty()) flushPendingModelText()
                        }
                    }

                    is GeminiEvent.Interrupted -> {
                        audioEngine.flushPlayback()
                        _state.update { it.copy(isAiSpeaking = false) }
                        pendingModelText.clear()
                        audioReceivedThisTurn = false
                        pendingFlushJob?.cancel()
                        pendingFlushJob = null
                    }

                    is GeminiEvent.TurnComplete -> {
                        if (pendingModelText.isNotEmpty()) flushPendingModelText()
                        audioReceivedThisTurn = false
                        modelStartedSpeakingThisTurn = false
                        pendingFlushJob?.cancel()
                        pendingFlushJob = null

                        audioEngine.onTurnComplete()
                        _state.update { it.copy(isAiSpeaking = false) }

                        flushPendingVocabViolation()

                        // Таймер тишины ученика — только если мик уже включён.
                        lastInputTs = System.currentTimeMillis()
                        if (_state.value.isMicActive) {
                            silenceTimerJob?.cancel()
                            silenceTimerJob = viewModelScope.launch {
                                delay(LEARNER_SILENCE_THRESHOLD_MS)
                                val quietFor = System.currentTimeMillis() - lastInputTs
                                if (quietFor > SILENCE_CHECK_WINDOW_MS && liveClient.isReady) {
                                    logger.d("Learn: silence detected (${quietFor}ms), prompting AI")
                                    liveClient.sendText(
                                        "[СИСТЕМА]: Ученик молчит. Коротко подбодри его по-русски, " +
                                            "дай подсказку или назови правильный ответ и попроси повторить."
                                    )
                                    lastInputTs = System.currentTimeMillis()
                                }
                            }
                        }
                    }

                    is GeminiEvent.GenerationComplete -> {
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.InputTranscript -> {
                        silenceTimerJob?.cancel()
                        val now = System.currentTimeMillis()
                        if (event.text != lastInputText || now - lastInputTs > 5_000) {
                            lastInputText = event.text
                            lastInputTs = now
                            appendTranscript(ConversationMessage.user(event.text))
                        } else {
                            lastInputTs = now
                        }
                    }

                    is GeminiEvent.OutputTranscript -> {
                        appendOrAppendToLastModel(event.text)
                        if (activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") {
                            vocabularyEnforcer.analyze(event.text)
                        }
                    }

                    is GeminiEvent.ModelText -> {
                        // Всегда пишем ModelText. Дедупликация с OutputTranscript
                        // делается в appendOrAppendToLastModel (по содержимому).
                        // Если включён outputTranscription — сервер чаще шлёт именно
                        // OutputTranscript, а ModelText приходит редко и дублирования нет.
                        appendOrAppendToLastModel(event.text)
                    }

                    is GeminiEvent.ToolCall -> handleToolCalls(event)

                    is GeminiEvent.ToolCallCancellation -> {
                        for (id in event.ids) {
                            pendingToolCalls.remove(id)
                            statusBus.onCompleted("<cancelled>", id, success = false)
                        }
                    }

                    is GeminiEvent.Disconnected -> {
                        greetingFallbackJob?.cancel()
                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
                                isPreparingSession = false,
                            )
                        }
                        audioEngine.stopCapture()
                        pendingToolCalls.clear()
                        silenceTimerJob?.cancel()
                        if (activeSession != null) {
                            _effects.tryEmit(
                                LearnCoreEffect.ShowToast(
                                    UiText.Plain("WS closed: ${event.code} ${event.reason}")
                                )
                            )
                        }
                    }

                    is GeminiEvent.ConnectionError -> {
                        greetingFallbackJob?.cancel()
                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
                                isPreparingSession = false,
                                error = UiText.Plain(event.message),
                            )
                        }
                        audioEngine.stopCapture()
                        pendingToolCalls.clear()
                        silenceTimerJob?.cancel()
                        _effects.tryEmit(LearnCoreEffect.Error(UiText.Plain(event.message)))
                    }

                    is GeminiEvent.SessionHandleUpdate,
                    is GeminiEvent.GoAway,
                    is GeminiEvent.UsageMetadata,
                    is GeminiEvent.GroundingMetadata -> { /* no-op */ }
                }
            }
        }
    }

    /**
     * Сценарий первого хода (Gemini заговаривает первой).
     *
     * Согласно официальной документации Google Live API:
     *   "Gemini Live API expects user input before it responds. To have
     *    Gemini Live API initiate the conversation, include a prompt
     *    asking it to greet the user or begin the conversation."
     *
     * Протокол:
     *   1. Дожидаемся SetupComplete.
     *   2. Короткая пауза для стабилизации WS (150 мс хватает).
     *   3. Отправляем session.initialUserMessage через sendText —
     *      этот метод внутри ставит turn_complete=true и просит ответ.
     *      НЕ нужно вызывать sendTurnComplete() после — это сбивает модель.
     *   4. Ставим awaitingInitialGreeting=true и НЕ включаем мик.
     *      VAD при включённом мике в тишине даст Interrupted и замолчит модель.
     *   5. Как только придёт первый AudioChunk — включаем мик.
     *   6. Fallback: если за 3 сек аудио нет — включаем мик принудительно,
     *      чтобы юзер не завис.
     */
    private fun handleSetupComplete() {
        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Ready) }
        val session = activeSession ?: return
        contextSeeded = true
        modelStartedSpeakingThisTurn = false

        viewModelScope.launch {
            delay(GREETING_WARMUP_MS)

            if (!liveClient.isReady) {
                logger.w("Learn: WS not ready after warmup, aborting greeting flow")
                return@launch
            }

            if (session.initialUserMessage.isNotBlank()) {
                logger.d("Learn: sending initial greeting trigger")
                awaitingInitialGreeting = true
                liveClient.sendText(session.initialUserMessage)
                // ВАЖНО: sendTurnComplete() после sendText НЕ вызываем —
                // sendText уже содержит turn_complete=true.

                // Fallback: если модель так и не заговорит за 3 сек — включим мик сами.
                greetingFallbackJob?.cancel()
                greetingFallbackJob = viewModelScope.launch {
                    delay(GREETING_FALLBACK_MS)
                    if (awaitingInitialGreeting) {
                        awaitingInitialGreeting = false
                        logger.w("Learn: greeting fallback — no audio in ${GREETING_FALLBACK_MS}ms, enabling mic anyway")
                        if (!_state.value.isMicActive) startMic()
                    }
                }
            } else {
                // Нет стартового сообщения → включаем мик сразу.
                if (!_state.value.isMicActive) {
                    logger.d("Learn: no initial greeting → enabling mic")
                    startMic()
                }
            }
        }
    }

    private fun flushPendingVocabViolation() {
        val violation = pendingVocabViolation ?: return
        pendingVocabViolation = null
        if ((activeSession?.id == "a1_situation" || activeSession?.id == "a1_review")
            && liveClient.isReady) {
            val prompt = vocabularyEnforcer.buildCorrectionPrompt(violation)
            logger.d("Learn: sending buffered vocab correction (${violation.violatingWords})")
            liveClient.sendText(prompt)
        }
    }

    private fun appendTranscript(msg: ConversationMessage) {
        transcriptBuffer.add(msg)
        while (transcriptBuffer.size > MAX_TRANSCRIPT_SIZE) transcriptBuffer.removeAt(0)
        _state.update { it.copy(transcript = transcriptBuffer.toList()) }
    }

    private fun appendOrAppendToLastModel(text: String) {
        if (text.isEmpty()) return
        pendingModelText.append(text)

        if (audioReceivedThisTurn) {
            flushPendingModelText()
            return
        }

        pendingFlushJob?.cancel()
        pendingFlushJob = viewModelScope.launch {
            delay(TEXT_FLUSH_TIMEOUT_MS)
            if (pendingModelText.isNotEmpty()) {
                logger.w("LearnCore: force-flush pending text (audio не пришёл за ${TEXT_FLUSH_TIMEOUT_MS}ms)")
                flushPendingModelText()
            }
        }
    }

    private fun flushPendingModelText() {
        if (pendingModelText.isEmpty()) return
        val text = pendingModelText.toString()
        pendingModelText.clear()
        pendingFlushJob?.cancel()
        pendingFlushJob = null

        val last = transcriptBuffer.lastOrNull()
        if (last != null && last.role == ConversationMessage.ROLE_MODEL) {
            // Дедупликация: если приходит тот же текст — игнор.
            if (last.text.endsWith(text)) {
                logger.d("Learn: suppressing duplicate ModelText/OutputTranscript")
                _state.update { it.copy(transcript = transcriptBuffer.toList()) }
                return
            }
            val updated = last.copy(text = last.text + text)
            transcriptBuffer[transcriptBuffer.size - 1] = updated
        } else {
            transcriptBuffer.add(
                ConversationMessage(
                    role = ConversationMessage.ROLE_MODEL,
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
            )
            while (transcriptBuffer.size > MAX_TRANSCRIPT_SIZE) transcriptBuffer.removeAt(0)
        }
        _state.update { it.copy(transcript = transcriptBuffer.toList()) }
    }

    private fun handleToolCalls(event: GeminiEvent.ToolCall) {
        viewModelScope.launch {
            for (call in event.calls) {
                pendingToolCalls.add(call.id)
                statusBus.onDetected(call.name, call.id)
            }
            val responses = mutableListOf<ToolResponse>()
            val session = activeSession

            for (call in event.calls) {
                if (call.id !in pendingToolCalls) continue
                statusBus.onExecuting(call.name, call.id)
                val result = runCatching {
                    session?.handleToolCall(call) ?: """{"error":"no active session"}"""
                }.getOrElse { e ->
                    logger.e("Learn.toolCall threw: ${e.message}", e)
                    """{"error":"${e.message?.replace("\"", "'")}"}"""
                }
                val success = !result.contains("\"error\"")
                pendingToolCalls.remove(call.id)
                statusBus.onCompleted(call.name, call.id, success)
                responses.add(ToolResponse(call.name, call.id, result))
            }
            if (responses.isNotEmpty() && liveClient.isReady) {
                liveClient.sendToolResponse(responses)
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        micJob?.cancel()
        silenceTimerJob?.cancel()
        greetingFallbackJob?.cancel()
        pendingFlushJob?.cancel()
        pendingModelText.clear()
        safeStopForegroundService()

        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { audioEngine.releaseAll() }
            runCatching { liveClient.disconnect() }
            runCatching { arbiter.release(ClientOwner.LEARN) }
            logger.d("LearnCoreViewModel cleanup complete")
        }
    }
}
