// ═══════════════════════════════════════════════════════════
// ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/presentation/voice/VoiceViewModel.kt
// Изменения:
//   + suspend вызовы обёрнуты в coroutines
//   + HapticEngine подключён к playbackSync
//   + Tool calling через ToolRegistry.dispatch()
//   + Tool declarations в SessionConfig
//   + NetworkMonitor для мгновенного reconnect
//   + startNewSession() при каждом connect
//   + Notification permission tracking
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.presentation.voice

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.codeextractor.app.GeminiLiveForegroundService
import com.codeextractor.app.data.NetworkMonitor
import com.codeextractor.app.data.PersistentConversationRepository
import com.codeextractor.app.data.settings.AppSettings
import com.codeextractor.app.domain.AudioEngine
import com.codeextractor.app.domain.ConversationRepository
import com.codeextractor.app.domain.LiveClient
import com.codeextractor.app.domain.ToolResponse
import com.codeextractor.app.domain.tools.ToolRegistry
import com.codeextractor.app.presentation.voice.haptics.HapticEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import com.codeextractor.app.domain.avatar.AvatarAnimator
import com.codeextractor.app.domain.model.ConversationMessage
import com.codeextractor.app.domain.model.GeminiEvent
import com.codeextractor.app.domain.model.LatencyProfile
import com.codeextractor.app.domain.model.SessionConfig
import com.codeextractor.app.util.AppLogger
import com.codeextractor.app.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

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
    val avatarAnimator: AvatarAnimator
) : ViewModel() {

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

    init {
        observeSettings()
        observeGeminiEvents()
        initAudioPlayback()
        observeNetwork()
        avatarAnimator.start()
    }

    // ════════════════════════════════════════════════════════════
    //  INTENT DISPATCHER
    // ════════════════════════════════════════════════════════════

    fun onIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.SubmitApiKey         -> handleSubmitApiKey(intent.key)
            is VoiceIntent.Connect              -> handleConnect()
            is VoiceIntent.Disconnect           -> handleDisconnect()
            is VoiceIntent.ToggleMic            -> handleToggleMic()
            is VoiceIntent.SendText             -> handleSendText(intent.text)
            is VoiceIntent.SaveLog              -> handleSaveLog()
            is VoiceIntent.ClearConversation    -> handleClearConversation()

            is VoiceIntent.ToggleFullscreenScene -> _state.update {
                it.copy(isSceneFullscreen = !it.isSceneFullscreen)
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  NETWORK MONITOR — мгновенный reconnect при восстановлении
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

                    val sceneMode = com.codeextractor.app.domain.scene.SceneMode.from(settings.sceneMode)
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
                            // ═══ NEW ═══
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
                    // Применить громкость сразу — программный gain
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

    // ════════════════════════════════════════════════════════════
    //  SESSION CONFIG — с tool declarations
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
            // ═══ FIX: tool declarations теперь действительно отправляются ═══
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

    private fun handleSubmitBackupKey(key: String) {
        if (key.isNotEmpty() && key.length < 20) {
            _effects.tryEmit(VoiceEffect.ShowToast(UiText.Plain("Backup ключ слишком короткий")))
            return
        }
        viewModelScope.launch {
            settingsStore.updateData { it.copy(apiKeyBackup = key, autoRotateKeys = key.isNotEmpty()) }
        }
    }

    private fun handleConnect() {
        if (activeApiKey.isEmpty()) return
        val status = _state.value.connectionStatus
        if (status == ConnectionStatus.Connecting || status == ConnectionStatus.Ready) return

        contextSeeded = false
        _state.update { it.copy(connectionStatus = ConnectionStatus.Connecting) }

        (conversationRepository as? PersistentConversationRepository)?.startNewSession()

        // ВАЖНО: Мы больше НЕ блокируем подключение к WebSocket из-за микрофона!
        // Сокет должен подключиться, чтобы кнопка стала зеленой ("Ready").
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                appContext.startForegroundService(GeminiLiveForegroundService.startIntent(appContext))
            } catch (e: Exception) {
                logger.w("ForegroundService start failed: ${e.message}")
            }
        }

        // Подключаем WebSocket в любом случае
        viewModelScope.launch {
            liveClient.connect(
                apiKey = activeApiKey,
                config = buildSessionConfig(),
                logRaw = cachedSettings.logRawWebSocketFrames
            )
        }
    }

    private fun handleDisconnect() {
        reconnectJob?.cancel()
        micJob?.cancel()
        viewModelScope.launch {
            audioEngine.stopCapture()
            liveClient.disconnect()
            _state.update {
                it.copy(connectionStatus = ConnectionStatus.Disconnected,
                    isMicActive = false, isAiSpeaking = false)
            }
        }
        try { appContext.startService(GeminiLiveForegroundService.stopIntent(appContext)) }
        catch (_: Exception) { }
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
                it.copy(isMicActive = false,
                    connectionStatus = if (liveClient.isReady) ConnectionStatus.Ready
                    else ConnectionStatus.Disconnected)
            }
        }
    }

    private fun handleSendText(text: String) {
        if (text.isBlank()) return
        liveClient.sendText(text)
        viewModelScope.launch {
            conversationRepository.add(ConversationMessage.user(text))
            _state.update { it.copy(transcript = conversationRepository.getAll()) }
        }
    }

    private fun handleClearConversation() {
        viewModelScope.launch {
            conversationRepository.clear()
            _state.update { it.copy(transcript = emptyList()) }
        }
    }

    private fun handleSaveLog() {
        _effects.tryEmit(VoiceEffect.SaveLogToFile(logger.getFullLog()))
    }

    private fun updateSetting(transform: AppSettings.() -> AppSettings) {
        viewModelScope.launch { settingsStore.updateData { it.transform() } }
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

                        // Seed истории ТОЛЬКО если:
                        //   1) мы ещё не засеивали её в этой сессии
                        //   2) нет sessionHandle (это не transparent-reconnect)
                        //   3) history не пуст
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
                        conversationRepository.add(ConversationMessage.user(event.text))
                        _state.update { it.copy(transcript = conversationRepository.getAll()) }
                    }

                    is GeminiEvent.OutputTranscript -> {
                        conversationRepository.appendOrAdd(ConversationMessage.ROLE_MODEL, event.text)
                        avatarAnimator.feedModelText(event.text)
                        _state.update { it.copy(transcript = conversationRepository.getAll()) }
                    }

                    is GeminiEvent.ModelText -> {
                        conversationRepository.appendOrAdd(ConversationMessage.ROLE_MODEL, event.text)
                        avatarAnimator.feedModelText(event.text)
                        _state.update { it.copy(transcript = conversationRepository.getAll()) }
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
                            _state.update { it.copy(
                                promptTokens = event.promptTokens,
                                responseTokens = event.responseTokens,
                                totalTokens = event.totalTokens
                            ) }
                        }
                    }

                    is GeminiEvent.GroundingMetadata -> { }

                    is GeminiEvent.Disconnected -> {
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected, isMicActive = false) }
                        _effects.tryEmit(VoiceEffect.ShowToast(
                            UiText.Plain("WS closed: code=${event.code} reason='${event.reason}'")
                        ))
                        audioEngine.stopCapture()
                        scheduleReconnect()
                    }

                    is GeminiEvent.ConnectionError -> {
                        val isRateLimit = event.message.contains("429") ||
                                event.message.contains("rate", ignoreCase = true)
                        if (isRateLimit && cachedSettings.autoRotateKeys && cachedSettings.apiKeyBackup.isNotEmpty()) {
                            activeApiKey = if (activeApiKey == cachedSettings.apiKey)
                                cachedSettings.apiKeyBackup else cachedSettings.apiKey
                            logger.d("Rate limit — rotating to backup key")
                        }
                        _state.update { it.copy(
                            connectionStatus = ConnectionStatus.Disconnected,
                            isMicActive = false, error = UiText.Plain(event.message)
                        ) }
                        _effects.tryEmit(VoiceEffect.ShowToast(
                            UiText.Plain("Ошибка: ${event.message.take(160)}")
                        ))
                        audioEngine.stopCapture()
                        scheduleReconnect()
                    }
                }

                if (cachedSettings.showDebugLog) {
                    _state.update { it.copy(logText = logger.getDisplayLog()) }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  TOOL CALLING — через ToolRegistry.dispatch()
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
            _effects.tryEmit(VoiceEffect.ShowToast(UiText.Plain(
                "Соединение потеряно после $maxAttempts попыток. Проверьте ключ и модель в настройках."
            )))
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
        // ═══ FIX: HapticEngine подключён к аудиопотоку ═══
        viewModelScope.launch {
            hapticEngine.attachToAudioStream(audioEngine.playbackSync)
        }
    }

    override fun onCleared() {
        super.onCleared()
        reconnectJob?.cancel(); micJob?.cancel()
        avatarAnimator.stop()
        viewModelScope.launch {
            audioEngine.releaseAll()
            liveClient.disconnect()
        }
    }
}