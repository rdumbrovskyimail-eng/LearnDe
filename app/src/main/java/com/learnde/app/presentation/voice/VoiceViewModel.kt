// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/presentation/voice/VoiceViewModel.kt
//
// Изменения v2 (этап 1 рефакторинга):
//  [5.1] VAD-sensitivity мапится в enum-строки, удалён vadSilenceTimeoutMs
//  [5.2] observeLearnSessions — distinctUntilChanged вместо firstEmission
//  [5.3] restartTick → restartSignal (SharedFlow<Unit>)
//  [5.4] SetupComplete: без delay(300) между sendText и startMic
//  [5.5] stopMic: убран двойной сигнал (audioStreamEnd ИЛИ turnComplete)
//  [5.6] handleSendText: sendText(initial) vs sendRealtimeText(in-dialog)
//  [5.7] pendingToolCalls.clear() в disconnect/enter/exit/Disconnected/Error
//  [5.8] dedup транскрипций на reconnect (in/out, окно 5 сек)
//  [5.9] exitLearnMode: без handleConnect(), мягкий выход в Disconnected
//  [5.10] scheduleReconnect: skip если активна Learn-сессия
//
// ⚠️ После Этапа 2 часть логики (observeLearnSessions, exitLearnMode,
// часть scheduleReconnect) уедет в отдельный координатор. Пока —
// переходный мягкий вариант.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.GeminiLiveForegroundService
import com.learnde.app.data.BackgroundImageStore
import com.learnde.app.data.NetworkMonitor
import com.learnde.app.data.PersistentConversationRepository
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.ConversationRepository
import com.learnde.app.domain.LiveClient
import com.learnde.app.domain.ToolResponse
import com.learnde.app.domain.avatar.AvatarAnimator
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.LatencyProfile
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.domain.scene.SceneMode
import com.learnde.app.domain.tools.ToolRegistry
import com.learnde.app.learn.core.ActiveClientArbiter
import com.learnde.app.learn.core.ClientOwner
import com.learnde.app.presentation.voice.haptics.HapticEngine
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @com.learnde.app.learn.core.VoiceScope private val liveClient: LiveClient,
    @com.learnde.app.learn.core.VoiceScope private val audioEngine: AudioEngine,
    private val conversationRepository: ConversationRepository,
    private val logger: AppLogger,
    private val settingsStore: DataStore<AppSettings>,
    private val toolRegistry: ToolRegistry,
    private val hapticEngine: HapticEngine,
    private val networkMonitor: NetworkMonitor,
    val avatarAnimator: AvatarAnimator,
    private val backgroundStore: BackgroundImageStore,
    private val arbiter: ActiveClientArbiter
) : ViewModel() {

    val audioPlaybackFlow get() = audioEngine.playbackSync
    val backgroundBitmap = backgroundStore.bitmap

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VoiceEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    @Volatile private var cachedSettings: AppSettings = AppSettings()
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var micJob: Job? = null
    @Volatile private var contextSeeded = false
    @Volatile private var activeApiKey: String = ""
    private val pendingToolCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val pendingSelfCloseEvents = AtomicInteger(0)
    private val modeSwitchMutex = Mutex()

    // Dedup guards для транскрипций — защита от дублей при reconnect.
    @Volatile private var lastInputTranscript: String = ""
    @Volatile private var lastInputTranscriptTime: Long = 0L
    @Volatile private var lastOutputTranscript: String = ""
    @Volatile private var lastOutputTranscriptTime: Long = 0L

    init {
        observeArbiter()
        observeSettings()
        observeGeminiEvents()
        observeTranscript()
        initAudioPlayback()
        observeNetwork()
        avatarAnimator.start()
    }

    // ════════════════════════════════════════════════════════════
    //  INTENT DISPATCHER
    // ════════════════════════════════════════════════════════════

    fun onIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.SubmitApiKey          -> handleSubmitApiKey(intent.key)
            is VoiceIntent.Connect               -> {
                viewModelScope.launch {
                    arbiter.acquire(ClientOwner.VOICE)
                    handleConnectInner()
                }
            }
            is VoiceIntent.Disconnect            -> handleDisconnect()
            is VoiceIntent.ToggleMic             -> handleToggleMic()
            is VoiceIntent.SendText              -> handleSendText(intent.text)
            is VoiceIntent.SaveLog               -> handleSaveLog()
            is VoiceIntent.ClearConversation     -> handleClearConversation()
            is VoiceIntent.ToggleFullscreenScene -> _state.update {
                it.copy(isSceneFullscreen = !it.isSceneFullscreen)
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  TRANSCRIPT
    // ════════════════════════════════════════════════════════════

    private fun observeTranscript() {
        viewModelScope.launch {
            conversationRepository.getAllFlow()
                .catch { e -> logger.e("Transcript flow error: ${e.message}") }
                .collect { list ->
                    _state.update { it.copy(transcript = list) }
                }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  NETWORK
    // ════════════════════════════════════════════════════════════

    private fun observeNetwork() {
        viewModelScope.launch {
            var wasDisconnected = false
            networkMonitor.isConnected.collect { connected ->
                if (!connected) {
                    wasDisconnected = true
                } else if (wasDisconnected) {
                    wasDisconnected = false
                    if (_state.value.connectionStatus == ConnectionStatus.Disconnected &&
                        activeApiKey.isNotEmpty()
                    ) {
                        logger.d("Network restored → reconnecting")
                        reconnectAttempt = 0
                        handleConnect()
                    }
                }
            }
        }
    }

    private fun observeArbiter() {
        viewModelScope.launch {
            arbiter.active.collect { owner ->
                when (owner) {
                    ClientOwner.LEARN -> {
                        // Learn забрал клиент — мы должны немедленно закрыть WS
                        if (_state.value.connectionStatus != ConnectionStatus.Disconnected) {
                            logger.d("Voice: arbiter=LEARN → disconnecting Voice client")
                            reconnectJob?.cancel()
                            micJob?.cancel()
                            audioEngine.stopCapture()
                            pendingToolCalls.clear()
                            pendingSelfCloseEvents.incrementAndGet()
                            liveClient.disconnect()
                            _state.update {
                                it.copy(
                                    connectionStatus = ConnectionStatus.Disconnected,
                                    isMicActive = false,
                                    isAiSpeaking = false,
                                )
                            }
                        }
                    }
                    ClientOwner.VOICE, ClientOwner.NONE -> {
                        // Мы активные (или никто не активный) — можно подключаться,
                        // но автоподключение только при наличии ключа и если пользователь
                        // явно открыл VoiceScreen (там уже есть startConnection логика)
                        logger.d("Voice: arbiter=$owner — ok")
                    }
                }
            }
        }
    }



    // ════════════════════════════════════════════════════════════
    //  SETTINGS
    // ════════════════════════════════════════════════════════════

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.data
                .catch { e -> logger.e("DataStore read error: ${e.message}"); emit(AppSettings()) }
                .collect { settings ->
                    val wasKeyEmpty = cachedSettings.apiKey.isEmpty()
                    cachedSettings = settings
                    activeApiKey = settings.apiKey

                    val profile = runCatching {
                        enumValueOf<LatencyProfile>(settings.latencyProfile)
                    }.getOrDefault(LatencyProfile.UltraLow)

                    val hasKey = settings.apiKey.isNotEmpty()
                    val sceneMode = SceneMode.from(settings.sceneMode)

                    _state.update {
                        it.copy(
                            apiKeySet = hasKey, showApiKeyInput = !hasKey,
                            currentVoiceId = settings.voiceId,
                            currentLatencyProfile = profile,
                            useAec = settings.useAec,
                            showDebugLog = settings.showDebugLog,
                            temperature = settings.temperature,
                            topP = settings.topP,
                            topK = settings.topK,
                            maxOutputTokens = settings.maxOutputTokens,
                            model = settings.model,
                            systemInstruction = settings.systemInstruction,
                            enableGoogleSearch = settings.enableGoogleSearch,
                            enableCompression = settings.enableContextCompression,
                            enableResumption = settings.enableSessionResumption,
                            languageCode = settings.languageCode,
                            logRawFrames = settings.logRawWebSocketFrames,
                            showUsageMetadata = settings.showUsageMetadata,
                            playbackVolume = settings.playbackVolume,
                            forceSpeakerOutput = settings.forceSpeakerOutput,
                            sceneMode = sceneMode,
                            sceneBgHasImage = settings.sceneBgHasImage,
                            chatFontScale = settings.chatFontScale,
                            chatShowRoleLabels = settings.chatShowRoleLabels,
                            chatShowTimestamps = settings.chatShowTimestamps,
                            chatAutoScroll = settings.chatAutoScroll,
                            chatBackgroundAlpha = settings.chatBackgroundAlpha
                        )
                    }
                    audioEngine.setPlaybackVolume(settings.playbackVolume / 100f)
                    audioEngine.setMicGain(settings.micGain / 100f)
                    audioEngine.setSpeakerRouting(settings.forceSpeakerOutput)

                    if (hasKey && wasKeyEmpty &&
                        _state.value.connectionStatus == ConnectionStatus.Disconnected
                    ) {
                        handleConnect()
                    }
                }
        }
    }

    private fun buildSessionConfig(): SessionConfig {
        val profile = runCatching {
            enumValueOf<LatencyProfile>(cachedSettings.latencyProfile)
        }.getOrDefault(LatencyProfile.UltraLow)

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
            // [5.1] VAD sensitivity — enum-строки v1beta API
            vadStartSensitivity = if (cachedSettings.vadStartOfSpeechSensitivity > 0.5f)
                "START_SENSITIVITY_HIGH" else "START_SENSITIVITY_LOW",
            vadEndSensitivity = if (cachedSettings.vadEndOfSpeechSensitivity > 0.5f)
                "END_SENSITIVITY_HIGH" else "END_SENSITIVITY_LOW",
            vadSilenceDurationMs = if (cachedSettings.vadSilenceTimeoutMs > 0)
                cachedSettings.vadSilenceTimeoutMs else 100,
            vadPrefixPaddingMs = 20,
            systemInstruction = cachedSettings.systemInstruction,
            inputTranscription = cachedSettings.inputTranscription,
            outputTranscription = cachedSettings.outputTranscription,
            enableSessionResumption = cachedSettings.enableSessionResumption,
            transparentResumption = cachedSettings.transparentResumption,
            sessionHandle = liveClient.sessionHandle,
            enableContextCompression = cachedSettings.enableContextCompression,
            compressionTriggerTokens = cachedSettings.compressionTriggerTokens,
            compressionTargetTokens = cachedSettings.compressionTargetTokens,
            enableGoogleSearch = cachedSettings.enableGoogleSearch,
            sendAudioStreamEnd = cachedSettings.sendAudioStreamEnd,
            functionDeclarations = if (cachedSettings.enableTestFunctions)
                toolRegistry.getFunctionDeclarationConfigs()
            else
                toolRegistry.getFunctionDeclarationConfigs().filter {
                    it.name == "get_current_time" || it.name == "get_device_status"
                },
        )
    }

    // ════════════════════════════════════════════════════════════
    //  HANDLERS
    // ════════════════════════════════════════════════════════════

    private fun handleSubmitApiKey(key: String) {
        if (key.length < 20) {
            _effects.tryEmit(VoiceEffect.ShowToast(UiText.Plain("Ключ слишком короткий")))
            return
        }
        viewModelScope.launch { settingsStore.updateData { it.copy(apiKey = key) } }
    }

    /**
     * Точка входа для всех внутренних вызовов connect (reconnect, network restore,
     * settings change). Захватывает Arbiter как VOICE и делегирует
     * в handleConnectInner(). Безопасен для вызова из любой корутины.
     */
    private fun handleConnect() {
        viewModelScope.launch {
            arbiter.acquire(ClientOwner.VOICE)
            handleConnectInner()
        }
    }

    private fun handleConnectInner() {
        if (activeApiKey.isEmpty()) return
        val status = _state.value.connectionStatus
        if (status == ConnectionStatus.Connecting || status == ConnectionStatus.Ready) return

        contextSeeded = false
        _state.update { it.copy(connectionStatus = ConnectionStatus.Connecting) }

        (conversationRepository as? PersistentConversationRepository)?.startNewSession()

        if (ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                appContext.startForegroundService(
                    GeminiLiveForegroundService.startIntent(
                        appContext, cachedSettings.forceSpeakerOutput
                    )
                )
            } catch (e: Exception) {
                logger.w("ForegroundService start failed: ${e.message}")
            }
        }

        viewModelScope.launch {
            try {
                liveClient.connect(
                    apiKey = activeApiKey,
                    config = buildSessionConfig(),
                    logRaw = cachedSettings.logRawWebSocketFrames
                )
            } catch (e: Exception) {
                logger.e("liveClient.connect error: ${e.message}", e)
                _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected) }
            }
        }
    }

    private fun handleDisconnect() {
        reconnectJob?.cancel(); micJob?.cancel()
        viewModelScope.launch {
            audioEngine.stopCapture()
            pendingToolCalls.clear()             // [5.7]
            pendingSelfCloseEvents.incrementAndGet()
            liveClient.disconnect()
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.Disconnected,
                    isMicActive = false, isAiSpeaking = false
                )
            }
        }
        try { appContext.startService(GeminiLiveForegroundService.stopIntent(appContext)) } catch (_: Exception) { }
    }

    private fun handleToggleMic() {
        if (_state.value.isMicActive) stopMic()
        else if (_state.value.connectionStatus == ConnectionStatus.Ready) startMic()
    }

    private fun startMic() {
        // 1. Защита: проверяем разрешение на уровне ядра
        val hasMic = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            logger.w("startMic called but RECORD_AUDIO permission is missing!")
            return
        }

        // 2. Защита: гарантируем, что Foreground Service запущен.
        // Если он уже работает, Android просто проигнорирует этот вызов.
        // Без FGS система убьет запись аудио через пару секунд.
        try {
            appContext.startForegroundService(
                GeminiLiveForegroundService.startIntent(
                    appContext, cachedSettings.forceSpeakerOutput
                )
            )
        } catch (e: Exception) {
            logger.w("ForegroundService start failed in startMic: ${e.message}")
        }

        _state.update { it.copy(isMicActive = true, connectionStatus = ConnectionStatus.Recording) }
        micJob = viewModelScope.launch {
            launch { audioEngine.micOutput.collect { chunk -> liveClient.sendAudio(chunk) } }
            audioEngine.startCapture()
        }
    }

    private fun stopMic() {
        micJob?.cancel(); micJob = null
        viewModelScope.launch {
            audioEngine.stopCapture()
            // [5.5] Один сигнал на конец хода — не оба.
            //   • sendAudioStreamEnd: сервер сам выдаст turnComplete
            //   • иначе при ручном VAD: явный turnComplete
            //   • при серверном VAD без audioStreamEnd: ничего не шлём,
            //     VAD сам определит конец речи.
            if (cachedSettings.sendAudioStreamEnd) {
                liveClient.sendAudioStreamEnd()
            } else if (!cachedSettings.enableServerVad) {
                liveClient.sendTurnComplete()
            }
            _state.update {
                it.copy(
                    isMicActive = false,
                    connectionStatus = if (liveClient.isReady) ConnectionStatus.Ready
                    else ConnectionStatus.Disconnected
                )
            }
        }
    }

    private fun handleSendText(text: String) {
        if (text.isBlank()) return
        // [5.6] Первое сообщение (до первого model turn) → clientContent.turns.
        // Любое последующее → realtimeInput.text (отдельный канал live-ввода).
        if (contextSeeded && _state.value.transcript.isNotEmpty()) {
            liveClient.sendRealtimeText(text)
        } else {
            liveClient.sendText(text)
        }
        viewModelScope.launch { conversationRepository.add(ConversationMessage.user(text)) }
    }

    private fun handleClearConversation() {
        viewModelScope.launch { conversationRepository.clear() }
    }

    private fun handleSaveLog() {
        _effects.tryEmit(VoiceEffect.SaveLogToFile(logger.getFullLog()))
    }

    // ════════════════════════════════════════════════════════════
    //  GEMINI EVENTS
    // ════════════════════════════════════════════════════════════

    private fun observeGeminiEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is GeminiEvent.Connected ->
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Negotiating) }

                    is GeminiEvent.SetupComplete -> {
                        reconnectAttempt = 0
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Ready) }

                        if (!contextSeeded && liveClient.sessionHandle == null) {
                            val history = conversationRepository.getAll()
                            if (history.isNotEmpty()) {
                                logger.d("Seeding initial context (${history.size} msgs)")
                                liveClient.restoreContext(history)
                            }
                        } else if (liveClient.sessionHandle != null) {
                            logger.d("Resumed via sessionHandle — skip manual context seed")
                        }
                        contextSeeded = true
                    }

                    is GeminiEvent.AudioChunk -> {
                        _state.update { it.copy(isAiSpeaking = true) }
                        audioEngine.enqueuePlayback(event.pcmData)
                        avatarAnimator.setSpeaking(true)
                    }

                    is GeminiEvent.Interrupted -> {
                        audioEngine.flushPlayback()
                        avatarAnimator.bargeInClear()
                        avatarAnimator.setSpeaking(false)
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.TurnComplete -> {
                        audioEngine.onTurnComplete()
                        avatarAnimator.setSpeaking(false)
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.GenerationComplete -> {
                        avatarAnimator.setSpeaking(false)
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.InputTranscript -> {
                        // [5.8] Защита от дублей при reconnect
                        val now = System.currentTimeMillis()
                        if (event.text != lastInputTranscript ||
                            now - lastInputTranscriptTime > 5_000
                        ) {
                            lastInputTranscript = event.text
                            lastInputTranscriptTime = now
                            conversationRepository.add(ConversationMessage.user(event.text))
                        }
                    }

                    is GeminiEvent.OutputTranscript -> {
                        // [5.8] Аналогично для output-транскрипции
                        val now = System.currentTimeMillis()
                        if (event.text != lastOutputTranscript ||
                            now - lastOutputTranscriptTime > 5_000
                        ) {
                            lastOutputTranscript = event.text
                            lastOutputTranscriptTime = now
                            conversationRepository.appendOrAdd(ConversationMessage.ROLE_MODEL, event.text)
                        }
                        avatarAnimator.feedModelText(event.text)
                    }

                    is GeminiEvent.ModelText -> {
                        conversationRepository.appendOrAdd(ConversationMessage.ROLE_MODEL, event.text)
                        avatarAnimator.feedModelText(event.text)
                    }

                    is GeminiEvent.ToolCall -> handleToolCalls(event)

                    is GeminiEvent.ToolCallCancellation -> {
                        for (id in event.ids) pendingToolCalls.remove(id)
                    }

                    is GeminiEvent.SessionHandleUpdate -> { /* no-op */ }

                    is GeminiEvent.GoAway -> {
                        reconnectAttempt = 0
                        scheduleReconnect(proactive = true)
                    }

                    is GeminiEvent.UsageMetadata -> {
                        if (cachedSettings.showUsageMetadata) {
                            _state.update {
                                it.copy(
                                    promptTokens = event.promptTokens,
                                    responseTokens = event.responseTokens,
                                    totalTokens = event.totalTokens
                                )
                            }
                        }
                    }

                    is GeminiEvent.GroundingMetadata -> { }

                    is GeminiEvent.Disconnected -> {
                        _state.update {
                            it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                                isMicActive = false
                            )
                        }
                        pendingToolCalls.clear()         // [5.7]
                        audioEngine.stopCapture()
                        if (consumeSelfCloseIfAny()) {
                            logger.d("WS closed by self — no reconnect")
                        } else {
                            _effects.tryEmit(
                                VoiceEffect.ShowToast(
                                    UiText.Plain("WS closed: code=${event.code} reason='${event.reason}'")
                                )
                            )
                            scheduleReconnect()
                        }
                    }

                    is GeminiEvent.ConnectionError -> {
                        val isRateLimit = event.message.contains("429") ||
                                event.message.contains("rate", ignoreCase = true)
                        if (isRateLimit && cachedSettings.autoRotateKeys &&
                            cachedSettings.apiKeyBackup.isNotEmpty()
                        ) {
                            activeApiKey = if (activeApiKey == cachedSettings.apiKey)
                                cachedSettings.apiKeyBackup else cachedSettings.apiKey
                            logger.d("Rate limit — rotating to backup key")
                        }
                        _state.update {
                            it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                                isMicActive = false, error = UiText.Plain(event.message)
                            )
                        }
                        pendingToolCalls.clear()         // [5.7]
                        audioEngine.stopCapture()
                        if (consumeSelfCloseIfAny()) {
                            logger.d("WS error during self-disconnect — ignored")
                        } else {
                            _effects.tryEmit(
                                VoiceEffect.ShowToast(
                                    UiText.Plain("Ошибка: ${event.message.take(160)}")
                                )
                            )
                            scheduleReconnect()
                        }
                    }
                }

                if (cachedSettings.showDebugLog) {
                    _state.update { it.copy(logText = logger.getDisplayLog()) }
                }
            }
        }
    }

    private fun consumeSelfCloseIfAny(): Boolean {
        while (true) {
            val n = pendingSelfCloseEvents.get()
            if (n <= 0) return false
            if (pendingSelfCloseEvents.compareAndSet(n, n - 1)) return true
        }
    }

    // ════════════════════════════════════════════════════════════
    //  TOOL CALLING
    // ════════════════════════════════════════════════════════════

    private suspend fun handleToolCalls(event: GeminiEvent.ToolCall) {
        for (call in event.calls) pendingToolCalls.add(call.id)

        val responses = mutableListOf<ToolResponse>()

        for (call in event.calls) {
            if (call.id !in pendingToolCalls) continue

            val result = toolRegistry.dispatch(call)

            pendingToolCalls.remove(call.id)
            responses.add(ToolResponse(call.name, call.id, result))
        }
        if (responses.isNotEmpty()) liveClient.sendToolResponse(responses)
    }

    // ════════════════════════════════════════════════════════════
    //  RECONNECT
    // ════════════════════════════════════════════════════════════

    private fun scheduleReconnect(proactive: Boolean = false) {
        val maxAttempts = cachedSettings.maxReconnectAttempts
        if (reconnectAttempt >= maxAttempts && !proactive) {
            _effects.tryEmit(
                VoiceEffect.ShowToast(
                    UiText.Plain(
                        "Соединение потеряно после $maxAttempts попыток. Проверьте ключ и модель в настройках."
                    )
                )
            )
            reconnectAttempt = 0
            return
        }
        val baseDelay = cachedSettings.reconnectBaseDelayMs
        val maxDelay = cachedSettings.reconnectMaxDelayMs
        val delayMs = if (proactive) 1000L
        else (baseDelay * (1L shl reconnectAttempt)).coerceAtMost(maxDelay)

        if (!proactive) reconnectAttempt++
        _state.update { it.copy(connectionStatus = ConnectionStatus.Reconnecting) }

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            contextSeeded = false
            handleConnect()
        }
    }

    // ════════════════════════════════════════════════════════════
    //  AUDIO + HAPTIC
    // ════════════════════════════════════════════════════════════

    private fun initAudioPlayback() {
        viewModelScope.launch { audioEngine.initPlayback() }
        viewModelScope.launch {
            audioEngine.playbackSync.collect { pcmChunk -> avatarAnimator.feedAudio(pcmChunk) }
        }
        viewModelScope.launch {
            hapticEngine.attachToAudioStream(audioEngine.playbackSync)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        reconnectJob?.cancel(); micJob?.cancel()
        avatarAnimator.stop()
        try { appContext.startService(GeminiLiveForegroundService.stopIntent(appContext)) } catch (_: Exception) { }
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { audioEngine.releaseAll() }
            runCatching { liveClient.disconnect() }
            logger.d("VoiceViewModel cleanup complete")
        }
    }
}