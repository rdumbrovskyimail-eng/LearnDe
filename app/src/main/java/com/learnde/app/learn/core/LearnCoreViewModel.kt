// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnCoreViewModel.kt
//
// Автономный ViewModel учебного блока.
//
// АРХИТЕКТУРА:
//  • Инжектит СВОИ @LearnScope инстансы LiveClient и AudioEngine
//    (отдельный WebSocket, отдельный AudioRecord/AudioTrack).
//  • Читает настройки и API-ключ из общего SettingsStore (DataStore).
//    То есть юзер меняет voiceId/model в глобальных Settings — это сразу
//    применяется и к Learn при следующем connect().
//  • Подписан на ActiveClientArbiter: при Start() делает acquire(LEARN),
//    при Stop() делает release(LEARN). Voice автоматически отпустит
//    свой клиент через arbiter.active == LEARN → disconnect().
//  • Делегирует toolCall в активную LearnSession. Публикует фазы
//    (DETECTED/EXECUTING/COMPLETED) в LearnFunctionStatusBus для
//    live-индикатора внизу экрана.
//  • Никак не связан с VoiceViewModel — они существуют в разных
//    инстансах, просто читают одни и те же Settings/Repository.
//
// ВАЖНО: Learn-транскрипт ведётся в отдельном in-memory потоке и НЕ
// сохраняется в PersistentConversationRepository. Это специально — чтобы
// учебные диалоги не засоряли историю голосового клиента.
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
) : ViewModel() {

    private val _state = MutableStateFlow(LearnCoreState())
    val state: StateFlow<LearnCoreState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LearnCoreEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<LearnCoreEffect> = _effects.asSharedFlow()

    /**
     * Live-статус выполняемой функции Gemini — проксируется из LearnFunctionStatusBus.
     * Используется UI (CurrentFunctionBar) на всех экранах Learn-блока.
     */
    val functionStatus: StateFlow<FunctionStatus> = statusBus.status

    @Volatile private var cachedSettings: AppSettings = AppSettings()
    @Volatile private var activeApiKey: String = ""
    @Volatile private var activeSession: LearnSession? = null
    @Volatile private var contextSeeded = false

    private val pendingToolCalls = ConcurrentHashMap.newKeySet<String>()
    private val startStopMutex = Mutex()
    private var micJob: Job? = null

    // Dedup для транскрипта
    @Volatile private var lastInputTs: Long = 0L
    @Volatile private var lastInputText: String = ""
    @Volatile private var lastOutputTs: Long = 0L
    @Volatile private var lastOutputText: String = ""

    // In-memory transcript (не сохраняется в БД — учебный режим)
    private val transcriptBuffer = mutableListOf<ConversationMessage>()

    init {
        observeSettings()
        observeGeminiEvents()
        observeArbiter()
        viewModelScope.launch { audioEngine.initPlayback() }
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

        return SessionConfig(
            model = cachedSettings.model,
            temperature = cachedSettings.temperature,
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
            vadSilenceDurationMs = if (cachedSettings.vadSilenceTimeoutMs > 0)
                cachedSettings.vadSilenceTimeoutMs else 100,
            vadPrefixPaddingMs = 20,
            systemInstruction = finalSystemInstruction,
            inputTranscription = cachedSettings.inputTranscription,
            outputTranscription = cachedSettings.outputTranscription,
            enableSessionResumption = false,    // Learn-сессии короткие, резюмирование не нужно
            sessionHandle = null,
            enableContextCompression = false,
            enableGoogleSearch = false,         // В Learn отключено — чтобы не шумело
            functionDeclarations = session.functionDeclarations,
            sendAudioStreamEnd = cachedSettings.sendAudioStreamEnd,
        )
    }

    // ══════════════════════════════════════════════════════
    //  ARBITER
    // ══════════════════════════════════════════════════════

    private fun observeArbiter() {
        viewModelScope.launch {
            arbiter.active.collect { owner ->
                val owned = owner == ClientOwner.LEARN
                _state.update { it.copy(arbiterOwned = owned) }
                // Если мы потеряли владение (например, Voice форсировал) — сворачиваемся
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

                // 1. Захватываем Arbiter — Voice отпустит свой клиент
                arbiter.acquire(ClientOwner.LEARN)

                // 2. На всякий — закрываем возможный старый WS нашего же клиента
                runCatching { liveClient.disconnect() }

                // 3. Сброс буферов
                transcriptBuffer.clear()
                pendingToolCalls.clear()
                contextSeeded = false
                statusBus.reset()
                lastInputText = ""
                lastOutputText = ""

                // 4. onEnter у сессии
                session.onEnter()
                activeSession = session

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

                // 5. Connect
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
                audioEngine.stopCapture()
                runCatching { liveClient.disconnect() }
                runCatching { session?.onExit() }

                activeSession = null
                pendingToolCalls.clear()
                statusBus.reset()
                contextSeeded = false

                _state.update {
                    it.copy(
                        sessionId = null,
                        connectionStatus = LearnConnectionStatus.Disconnected,
                        isMicActive = false,
                        isAiSpeaking = false,
                    )
                }

                // Освобождаем Arbiter — Voice сам подцепится при входе в VoiceScreen
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
                audioEngine.stopCapture()
                runCatching { liveClient.disconnect() }
                runCatching { s.onExit() }

                transcriptBuffer.clear()
                pendingToolCalls.clear()
                statusBus.reset()
                contextSeeded = false
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
                    }

                    is GeminiEvent.Interrupted -> {
                        audioEngine.flushPlayback()
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.TurnComplete -> {
                        audioEngine.onTurnComplete()
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.GenerationComplete -> {
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.InputTranscript -> {
                        val now = System.currentTimeMillis()
                        if (event.text != lastInputText || now - lastInputTs > 5_000) {
                            lastInputText = event.text
                            lastInputTs = now
                            appendTranscript(ConversationMessage.user(event.text))
                        }
                    }

                    is GeminiEvent.OutputTranscript -> {
                        val now = System.currentTimeMillis()
                        if (event.text != lastOutputText || now - lastOutputTs > 5_000) {
                            lastOutputText = event.text
                            lastOutputTs = now
                            appendOrAppendToLastModel(event.text)
                        }
                    }

                    is GeminiEvent.ModelText -> {
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
                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
                            )
                        }
                        audioEngine.stopCapture()
                        pendingToolCalls.clear()
                        // В Learn мы НЕ делаем auto-reconnect — юзер осознанно нажимает "Начать заново".
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
                        _effects.tryEmit(LearnCoreEffect.Error(UiText.Plain(event.message)))
                    }

                    // Остальные события в Learn не используются
                    is GeminiEvent.SessionHandleUpdate,
                    is GeminiEvent.GoAway,
                    is GeminiEvent.UsageMetadata,
                    is GeminiEvent.GroundingMetadata -> { /* no-op */ }
                }
            }
        }
    }

    private fun appendTranscript(msg: ConversationMessage) {
        transcriptBuffer.add(msg)
        _state.update { it.copy(transcript = transcriptBuffer.toList()) }
    }

    /** Если последнее сообщение — MODEL, дописываем к нему; иначе новое. */
    private fun appendOrAppendToLastModel(text: String) {
        val last = transcriptBuffer.lastOrNull()
        if (last != null && last.role == ConversationMessage.ROLE_MODEL) {
            val updated = last.copy(text = last.text + text)
            transcriptBuffer[transcriptBuffer.size - 1] = updated
        } else {
            transcriptBuffer.add(ConversationMessage(
                role = ConversationMessage.ROLE_MODEL,
                text = text,
                timestamp = System.currentTimeMillis()
            ))
        }
        _state.update { it.copy(transcript = transcriptBuffer.toList()) }
    }

    // ══════════════════════════════════════════════════════
    //  TOOL CALLING
    // ══════════════════════════════════════════════════════

    private fun handleToolCalls(event: GeminiEvent.ToolCall) {
        viewModelScope.launch {
            // 1. DETECTED — мгновенно, до выполнения
            for (call in event.calls) {
                pendingToolCalls.add(call.id)
                statusBus.onDetected(call.name, call.id)
            }

            val responses = mutableListOf<ToolResponse>()
            val session = activeSession

            // 2. EXECUTING → COMPLETED параллельно
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
        // НЕ освобождаем audio/liveClient синхронно — дадим завершиться
        // в background (как в VoiceViewModel).
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { audioEngine.releaseAll() }
            runCatching { liveClient.disconnect() }
            runCatching { arbiter.release(ClientOwner.LEARN) }
            logger.d("LearnCoreViewModel cleanup complete")
        }
    }
}