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
import com.learnde.app.presentation.voice.haptics.HapticEngine
import com.learnde.app.util.AppLogger
import com.learnde.app.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * MVI ViewModel для голосового экрана.
 *
 * ФИКСЫ vs прошлая версия:
 *   [1] transcript обновляется ТОЛЬКО через conversationRepository.getAllFlow() —
 *       устранены race condition'ы и рассинхрон UI.
 *   [2] connectInFlight (AtomicBoolean) защищает от повторного connect, пока
 *       предыдущий в процессе (например, settings watcher + network restore + reconnect).
 *   [3] observeSettings не вызывает handleConnect если уже connectInFlight.
 *   [4] onCleared корректно останавливает сервис.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val liveClient: LiveClient,
    private val audioEngine: AudioEngine,
    private val conversationRepository: ConversationRepository,
    private val logger: AppLogger,
    private val settingsStore: DataStore<AppSettings>,
    private val toolRegistry: ToolRegistry,
    private val hapticEngine: HapticEngine,
    private val networkMonitor: NetworkMonitor,
    val avatarAnimator: AvatarAnimator,
    private val backgroundStore: BackgroundImageStore,
    private val a0a1TestBus: com.learnde.app.Learn.Test.A0a1.A0a1TestBus
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
    private val pendingToolCalls = mutableSetOf<String>()

    /** Защита от параллельных connect()-ов. */
    private val connectInFlight = AtomicBoolean(false)
    /** Счётчик ожидаемых событий закрытия, инициированных нами. */
    private val pendingSelfCloseEvents = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        observeSettings()
        observeGeminiEvents()
        observeTranscript()
        initAudioPlayback()
        observeNetwork()
        avatarAnimator.start()
        observeA0a1TestSignals()
    }

    // ════════════════════════════════════════════════════════════
    //  INTENT DISPATCHER
    // ════════════════════════════════════════════════════════════

    fun onIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.SubmitApiKey          -> handleSubmitApiKey(intent.key)
            is VoiceIntent.Connect               -> handleConnect()
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
    //  TRANSCRIPT — единственный источник правды: Flow из Room
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
    //  NETWORK MONITOR
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

    // ════════════════════════════════════════════════════════════
    //  A0-A1 TEST MODE
    // ════════════════════════════════════════════════════════════

    private fun observeA0a1TestSignals() {
        viewModelScope.launch {
            a0a1TestBus.startSignal.collect {
                logger.d("▶ A0a1 START signal")
                enterA0a1TestMode()
            }
        }
        viewModelScope.launch {
            a0a1TestBus.exitSignal.collect {
                logger.d("◀ A0a1 EXIT signal")
                exitA0a1TestMode()
            }
        }
    }

    /**
     * Переподключает сессию с systemInstruction для теста A0-A1.
     * После SetupComplete модели автоматически отправится "Начни тест.".
     */
    private fun enterA0a1TestMode() {
        _state.update { it.copy(a0a1TestActive = true) }
        viewModelScope.launch {
            reconnectJob?.cancel()
            micJob?.cancel()
            audioEngine.stopCapture()

            // ВАЖНО: помечаем, что закрытие — инициировано нами
            pendingSelfCloseEvents.incrementAndGet()
            liveClient.disconnect()
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.Disconnected,
                    isMicActive = false,
                    isAiSpeaking = false
                )
            }
            connectInFlight.set(false)

            delay(400)  // даём WS корректно закрыться

            if (activeApiKey.isEmpty()) {
                logger.w("A0a1: cannot start — API key empty")
                return@launch
            }
            if (!connectInFlight.compareAndSet(false, true)) return@launch
            contextSeeded = true  // в тест-режиме НЕ подмешиваем старую историю
            _state.update { it.copy(connectionStatus = ConnectionStatus.Connecting) }

            runCatching {
                liveClient.connect(
                    apiKey = activeApiKey,
                    config = buildA0a1SessionConfig(),
                    logRaw = cachedSettings.logRawWebSocketFrames
                )
            }.onFailure { e ->
                logger.e("A0a1 connect error: ${e.message}", e)
                connectInFlight.set(false)
                _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected) }
            }
        }
    }

    /** Возвращает сессию в обычный режим. */
    private fun exitA0a1TestMode() {
        if (!_state.value.a0a1TestActive) return
        _state.update { it.copy(a0a1TestActive = false) }
        viewModelScope.launch {
            reconnectJob?.cancel()
            micJob?.cancel()
            audioEngine.stopCapture()

            // ВАЖНО
            pendingSelfCloseEvents.incrementAndGet()
            liveClient.disconnect()
            connectInFlight.set(false)
            delay(400)
            contextSeeded = false
            handleConnect()
        }
    }

    /**
     * SessionConfig для режима теста:
     *   • подменяем systemInstruction,
     *   • в tools оставляем ТОЛЬКО award_points и finish_test
     *     (чтобы модель случайно не вызвала test_function_N),
     *   • отключаем googleSearch и resumption — сессия свежая.
     */
    private fun buildA0a1SessionConfig(): SessionConfig {
        val base = buildSessionConfig()
        val testDecls = toolRegistry.getFunctionDeclarationConfigs().filter { decl ->
            decl.name == com.learnde.app.Learn.Test.A0a1.A0a1TestRegistry.FN_AWARD_0 ||
            decl.name == com.learnde.app.Learn.Test.A0a1.A0a1TestRegistry.FN_AWARD_1 ||
            decl.name == com.learnde.app.Learn.Test.A0a1.A0a1TestRegistry.FN_AWARD_2 ||
            decl.name == com.learnde.app.Learn.Test.A0a1.A0a1TestRegistry.FN_AWARD_3 ||
            decl.name == com.learnde.app.Learn.Test.A0a1.A0a1TestRegistry.FN_FINISH
        }
        return base.copy(
            systemInstruction = com.learnde.app.Learn.Test.A0a1.A0a1TestRegistry.SYSTEM_INSTRUCTION,
            functionDeclarations = testDecls,
            enableGoogleSearch = false,
            sessionHandle = null,
            enableSessionResumption = false
        )
    }

    // ════════════════════════════════════════════════════════════
    //  SETTINGS → UI STATE
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

                    // Автоконнект ТОЛЬКО при первом появлении ключа и если нет уже летающего connect
                    if (hasKey && wasKeyEmpty &&
                        _state.value.connectionStatus == ConnectionStatus.Disconnected &&
                        !connectInFlight.get()
                    ) {
                        handleConnect()
                    }
                }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  SESSION CONFIG
    // ════════════════════════════════════════════════════════════

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
            vadStartSensitivity = cachedSettings.vadStartOfSpeechSensitivity,
            vadEndSensitivity = cachedSettings.vadEndOfSpeechSensitivity,
            vadSilenceTimeoutMs = cachedSettings.vadSilenceTimeoutMs,
            systemInstruction = cachedSettings.systemInstruction,
            inputTranscription = cachedSettings.inputTranscription,
            outputTranscription = cachedSettings.outputTranscription,
            enableSessionResumption = cachedSettings.enableSessionResumption,
            transparentResumption = cachedSettings.transparentResumption,
            sessionHandle = liveClient.sessionHandle,
            enableContextCompression = cachedSettings.enableContextCompression,
            compressionTriggerTokens = cachedSettings.compressionTriggerTokens,
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
        viewModelScope.launch {
            settingsStore.updateData { it.copy(apiKey = key) }
        }
    }

    private fun handleConnect() {
        if (activeApiKey.isEmpty()) return
        val status = _state.value.connectionStatus
        if (status == ConnectionStatus.Connecting || status == ConnectionStatus.Ready) return

        // ═══ КРИТИЧЕСКАЯ ЗАЩИТА ОТ ПОВТОРНОГО CONNECT ═══
        if (!connectInFlight.compareAndSet(false, true)) {
            logger.d("Connect skipped — already in flight")
            return
        }

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
                connectInFlight.set(false)
                _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected) }
            }
        }
    }

    private fun handleDisconnect() {
        reconnectJob?.cancel()
        micJob?.cancel()
        connectInFlight.set(false)
        viewModelScope.launch {
            audioEngine.stopCapture()
            liveClient.disconnect()
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.Disconnected,
                    isMicActive = false, isAiSpeaking = false
                )
            }
        }
        try {
            appContext.startService(GeminiLiveForegroundService.stopIntent(appContext))
        } catch (_: Exception) { }
    }

    private fun handleToggleMic() {
        if (_state.value.isMicActive) stopMic()
        else if (_state.value.connectionStatus == ConnectionStatus.Ready) startMic()
    }

    private fun startMic() {
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
            if (cachedSettings.sendAudioStreamEnd) liveClient.sendAudioStreamEnd()
            liveClient.sendTurnComplete()
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
        liveClient.sendText(text)
        viewModelScope.launch {
            conversationRepository.add(ConversationMessage.user(text))
        }
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
                        connectInFlight.set(false)
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
                        // В режиме теста A0-A1 — отправляем стартовую команду модели
                        if (_state.value.a0a1TestActive) {
                            logger.d("A0a1: sending start message to model")
                            liveClient.sendText("Начни тест.")
                            liveClient.sendTurnComplete()
                            // Авто-старт микрофона: юзер сразу может отвечать голосом
                            delay(300)
                            if (liveClient.isReady && !_state.value.isMicActive) {
                                logger.d("A0a1: auto-starting mic")
                                startMic()
                            }
                        }
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
                        conversationRepository.add(ConversationMessage.user(event.text))
                    }

                    is GeminiEvent.OutputTranscript -> {
                        conversationRepository.appendOrAdd(ConversationMessage.ROLE_MODEL, event.text)
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

                    is GeminiEvent.SessionHandleUpdate -> { /* handle stored in liveClient */ }

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
                        connectInFlight.set(false)
                        _state.update {
                            it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                                isMicActive = false
                            )
                        }
                        audioEngine.stopCapture()
                        // Мы сами закрыли сокет (например, при смене режима) — не реконнектим и не спамим toast
                        if (pendingSelfCloseEvents.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
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
                        connectInFlight.set(false)
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
                        audioEngine.stopCapture()
                        if (pendingSelfCloseEvents.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
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
            audioEngine.playbackSync.collect { pcmChunk ->
                avatarAnimator.feedAudio(pcmChunk)
            }
        }
        viewModelScope.launch {
            hapticEngine.attachToAudioStream(audioEngine.playbackSync)
        }
    }

    override fun onCleared() {
        super.onCleared()
        reconnectJob?.cancel(); micJob?.cancel()
        connectInFlight.set(false)
        avatarAnimator.stop()
        try {
            appContext.startService(GeminiLiveForegroundService.stopIntent(appContext))
        } catch (_: Exception) { }
        viewModelScope.launch {
            audioEngine.releaseAll()
            liveClient.disconnect()
        }
    }
}