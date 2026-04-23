// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.2
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnCoreViewModel.kt
//
// ИЗМЕНЕНИЯ v3.2 (критичные фиксы тормозов и багов):
//   1. vadSilenceDurationMs: 100 → 800 (было слишком агрессивно, резало речь)
//   2. vadPrefixPaddingMs: 20 → 300 (начало речи терялось)
//   3. silence timer: 6s → 10s + реальная проверка по lastInputTs
//   4. transcript FIFO limit 150 сообщений (было - бесконечный рост)
//   5. VocabularyViolation буферизуется до TurnComplete (не рвёт речь Gemini)
//   6. ForegroundService: специфичный catch для ForegroundServiceStartNotAllowedException
//   7. Для A1-сессии — особые VAD настройки (более длинные паузы для ученика)
//   8. Для translator — ещё более длинные паузы
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
        /** FIFO-лимит транскрипта — защита от бесконечного роста в долгих сессиях. */
        private const val MAX_TRANSCRIPT_SIZE = 150

        /** Текст-флаш таймаут (для старых fallback-путей). */
        private const val TEXT_FLUSH_TIMEOUT_MS = 2_000L

        /** Реальный порог тишины ученика перед промптом Gemini (было 6s — мало). */
        private const val LEARNER_SILENCE_THRESHOLD_MS = 10_000L

        /** Минимальная пауза от последнего InputTranscript, чтобы считать тишиной. */
        private const val SILENCE_CHECK_WINDOW_MS = 9_000L
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

    @Volatile private var lastInputTs: Long = 0L
    @Volatile private var lastInputText: String = ""
    @Volatile private var lastOutputTs: Long = 0L
    @Volatile private var lastOutputText: String = ""

    // In-memory transcript with FIFO limit
    private val transcriptBuffer = mutableListOf<ConversationMessage>()

    private val pendingModelText = StringBuilder()
    private var audioReceivedThisTurn = false
    private var pendingFlushJob: Job? = null

    // v3.2: Буфер для vocabulary violations — шлём после TurnComplete, не прерывая речь
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
                // v3.2: Не шлём сразу, а буферизуем до TurnComplete
                // (иначе прерываем текущую речь Gemini)
                if (activeSession?.id == "a1_situation") {
                    pendingVocabViolation = violation
                    logger.d("Learn: vocab violation buffered (${violation.violatingWords})")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  INTENT DISPATCHER
    // ══════════════════════════════════════════════════════

    fun onIntent(intent: LearnCoreIntent) {
        when (intent) {
            is LearnCoreIntent.Start     -> handleStart(intent.sessionId)
            is LearnCoreIntent.Stop      -> handleStop()
            is LearnCoreIntent.Restart   -> handleRestart()
            is LearnCoreIntent.ToggleMic -> handleToggleMic()
            is LearnCoreIntent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    // ══════════════════════════════════════════════════════
    //  SETTINGS
    // ══════════════════════════════════════════════════════

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
            "${session.systemInstruction}\n\n[ДАННЫЕ ПОЛЬЗОВАТЕЛЯ]:\nОбращайся к ученику по имени. Учитывай эти данные: $userInfo"
        } else {
            session.systemInstruction
        }

        // v3.2: VAD по типу сессии
        // Translator: ученик может говорить длинными фразами с паузами
        // Learn A1: ученик A1 думает долго между словами
        // Test: короткие ответы, можно быстрее
        val (silenceMs, prefixMs, temp) = when (session.id) {
            "translator"    -> Triple(1200, 400, 0.3f)   // Очень длинные паузы, низкая temp
            "a1_situation"  -> Triple(500, 100, cachedSettings.temperature) // Ускорили реакцию!
            "a1_review"     -> Triple(500, 100, cachedSettings.temperature)
            else            -> Triple(500, 100, cachedSettings.temperature)   
        }

        // Если юзер в настройках явно задал vadSilenceTimeoutMs > 0 — уважаем его выбор,
        // но не даём меньше минимального разумного значения (500ms).
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
            logger.d("Learn: built config for ${session.id}: silence=${finalSilenceMs}ms, prefix=${prefixMs}ms, temp=$temp")
        }
    }

    // ══════════════════════════════════════════════════════
    //  ARBITER
    // ══════════════════════════════════════════════════════

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
                val session = registry.get(sessionId)
                if (session == null) {
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
                lastOutputText = ""
                pendingVocabViolation = null

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

                _state.update {
                    it.copy(
                        sessionId = null,
                        connectionStatus = LearnConnectionStatus.Disconnected,
                        isMicActive = false,
                        isAiSpeaking = false,
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
            startStopMutex.withLock {
                logger.d("▶ Learn.handleRestart(${s.id})")
                micJob?.cancel()
                silenceTimerJob?.cancel()
                audioEngine.stopCapture()
                runCatching { liveClient.disconnect() }
                runCatching { s.onExit() }

                transcriptBuffer.clear()
                pendingToolCalls.clear()
                statusBus.reset()
                contextSeeded = false
                pendingVocabViolation = null

                _state.update {
                    it.copy(
                        transcript = emptyList(),
                        connectionStatus = LearnConnectionStatus.Connecting,
                        isMicActive = false,
                        isAiSpeaking = false,
                    )
                }

                s.onEnter()
                runCatching {
                    liveClient.connect(
                        apiKey = activeApiKey,
                        config = buildLearnSessionConfig(s),
                        logRaw = cachedSettings.logRawWebSocketFrames
                    )
                }.onFailure { e ->
                    logger.e("Learn.restart: ${e.message}", e)
                    _state.update {
                        it.copy(
                            connectionStatus = LearnConnectionStatus.Disconnected,
                            error = UiText.Plain("Restart failed: ${e.message}")
                        )
                    }
                }
            }
        }
    }

    private fun handleToggleMic() {
        if (_state.value.isMicActive) stopMic()
        else if (_state.value.connectionStatus == LearnConnectionStatus.Ready) startMic()
    }

    private fun startMic() {
        val hasMic = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            logger.w("Learn.startMic: no RECORD_AUDIO permission")
            return
        }

        // v3.2: Специфичный catch вместо generic Exception
        val fgsOk = safeStartForegroundService()
        if (!fgsOk) {
            _effects.tryEmit(LearnCoreEffect.ShowToast(
                UiText.Plain("Запусти обучение когда приложение на переднем плане")
            ))
            // Продолжаем — мик всё равно может работать, просто без FGS
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

    /** v3.2: Безопасный запуск FGS с правильным catch. */
    private fun safeStartForegroundService(): Boolean {
        return try {
            appContext.startForegroundService(
                com.learnde.app.GeminiLiveForegroundService.startIntent(
                    appContext, cachedSettings.forceSpeakerOutput
                )
            )
            true
        } catch (e: IllegalStateException) {
            // На Android 12+ это будет ForegroundServiceStartNotAllowedException,
            // который наследуется от IllegalStateException
            logger.w("FGS not allowed (app in background?): ${e.javaClass.simpleName}: ${e.message}")
            false
        } catch (e: SecurityException) {
            logger.e("FGS permission denied: ${e.message}")
            false
        } catch (e: Exception) {
            logger.e("FGS start failed with unexpected error: ${e.message}", e)
            false
        }
    }

    private fun safeStopForegroundService() {
        try {
            appContext.startService(
                com.learnde.app.GeminiLiveForegroundService.stopIntent(appContext)
            )
        } catch (_: Exception) { /* безопасно игнорируем — сервис мог быть уже остановлен */ }
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

                    is GeminiEvent.SetupComplete -> {
                        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Ready) }
                        val session = activeSession ?: return@collect
                        contextSeeded = true
                        if (session.initialUserMessage.isNotBlank()) {
                            liveClient.sendText(session.initialUserMessage)
                        }
                        if (!_state.value.isMicActive) {
                            logger.d("Learn: auto-starting mic after SetupComplete")
                            startMic()
                        }
                    }

                    is GeminiEvent.AudioChunk -> {
                        _state.update { it.copy(isAiSpeaking = true) }
                        audioEngine.enqueuePlayback(event.pcmData)
                        if (!audioReceivedThisTurn) {
                            audioReceivedThisTurn = true
                            if (pendingModelText.isNotEmpty()) {
                                flushPendingModelText()
                            }
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
                        if (pendingModelText.isNotEmpty()) {
                            flushPendingModelText()
                        }
                        audioReceivedThisTurn = false
                        pendingFlushJob?.cancel()
                        pendingFlushJob = null

                        audioEngine.onTurnComplete()
                        _state.update { it.copy(isAiSpeaking = false) }

                        // v3.2: Отправляем vocab violation ТЕПЕРЬ, когда Gemini завершил turn
                        flushPendingVocabViolation()

                        // v3.2: Silence timer — но только с реальной проверкой
                        if (_state.value.isMicActive) {
                            silenceTimerJob?.cancel()
                            silenceTimerJob = viewModelScope.launch {
                                delay(LEARNER_SILENCE_THRESHOLD_MS)
                                // Проверяем: действительно ли юзер молчал всё это время
                                val sinceLastInput = System.currentTimeMillis() - lastInputTs
                                if (sinceLastInput > SILENCE_CHECK_WINDOW_MS && liveClient.isReady) {
                                    logger.d("Learn: real silence detected (${sinceLastInput}ms), prompting AI")
                                    liveClient.sendText(
                                        "[СИСТЕМА]: Ученик молчит. Коротко подбодри его по-русски, " +
                                        "дай подсказку или назови правильный ответ и попроси повторить."
                                    )
                                }
                            }
                        }
                    }

                    is GeminiEvent.GenerationComplete -> {
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.InputTranscript -> {
                        // Ученик говорит — отменяем silence timer
                        silenceTimerJob?.cancel()

                        val now = System.currentTimeMillis()
                        if (event.text != lastInputText || now - lastInputTs > 5_000) {
                            lastInputText = event.text
                            lastInputTs = now
                            appendTranscript(ConversationMessage.user(event.text))
                        } else {
                            lastInputTs = now  // Обновляем таймстамп даже для дублей
                        }
                    }

                    is GeminiEvent.OutputTranscript -> {
                        appendOrAppendToLastModel(event.text)
                        if (activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") {
                            vocabularyEnforcer.analyze(event.text)
                        }
                    }

                    is GeminiEvent.ModelText -> {
                        // v3.2: Если outputTranscription включён — modelText дублирует.
                        // Пропускаем, чтобы не было двойного текста.
                        if (!cachedSettings.outputTranscription) {
                            appendOrAppendToLastModel(event.text)
                        }
                    }

                    is GeminiEvent.ToolCall -> handleToolCalls(event)

                    is GeminiEvent.ToolCallCancellation -> {
                        for (id in event.ids) {
                            pendingToolCalls.remove(id)
                            statusBus.onCompleted("<cancelled>", id, success = false)
                        }
                    }

                    is GeminiEvent.Disconnected -> {
                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
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
                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
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

    /** v3.2: Отправляем буферизованное vocab-нарушение после TurnComplete. */
    private fun flushPendingVocabViolation() {
        val violation = pendingVocabViolation ?: return
        pendingVocabViolation = null
        if ((activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") && liveClient.isReady) {
            val prompt = vocabularyEnforcer.buildCorrectionPrompt(violation)
            logger.d("Learn: sending buffered vocab correction (${violation.violatingWords})")
            liveClient.sendText(prompt)
        }
    }

    private fun appendTranscript(msg: ConversationMessage) {
        transcriptBuffer.add(msg)
        // v3.2: FIFO — не даём транскрипту расти бесконечно
        while (transcriptBuffer.size > MAX_TRANSCRIPT_SIZE) {
            transcriptBuffer.removeAt(0)
        }
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
            // FIFO проверка и здесь
            while (transcriptBuffer.size > MAX_TRANSCRIPT_SIZE) {
                transcriptBuffer.removeAt(0)
            }
        }
        _state.update { it.copy(transcript = transcriptBuffer.toList()) }
    }

    // ══════════════════════════════════════════════════════
    //  TOOL CALLING
    // ══════════════════════════════════════════════════════

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
                    session?.handleToolCall(call)
                        ?: """{"error":"no active session"}"""
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