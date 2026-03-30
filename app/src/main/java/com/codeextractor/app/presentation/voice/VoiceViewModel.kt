package com.codeextractor.app.presentation.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeextractor.app.data.InMemoryConversationStore
import com.codeextractor.app.data.SettingsStore
import com.codeextractor.app.domain.AudioEngine
import com.codeextractor.app.domain.LiveClient
import com.codeextractor.app.domain.ToolResponse
import com.codeextractor.app.domain.model.AppSettings
import com.codeextractor.app.domain.model.ConversationMessage
import com.codeextractor.app.domain.model.LatencyProfile
import com.codeextractor.app.domain.model.GeminiEvent
import com.codeextractor.app.domain.model.SessionConfig
import com.codeextractor.app.util.AppLogger
import com.codeextractor.app.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val liveClient: LiveClient,
    private val audioEngine: AudioEngine,
    private val conversationStore: InMemoryConversationStore,
    private val settingsStore: SettingsStore,
    private val logger: AppLogger
) : ViewModel() {

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
    }

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VoiceEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private var cachedSettings = AppSettings()
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var micJob: Job? = null

    init {
        observeSettings()
        observeGeminiEvents()
        initAudioPlayback()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                val wasKeyEmpty = cachedSettings.apiKey.isEmpty()
                cachedSettings = settings

                val profile = runCatching {
                    enumValueOf<LatencyProfile>(settings.latencyProfile)
                }.getOrDefault(LatencyProfile.UltraLow)

                val hasKey = settings.apiKey.isNotEmpty()

                _state.update {
                    it.copy(
                        apiKeySet             = hasKey,
                        showApiKeyInput       = !hasKey,
                        currentVoiceId        = settings.voiceId,
                        currentLatencyProfile = profile,
                        useAec                = settings.useAec,
                        showDebugLog          = settings.showDebugLog
                    )
                }

                if (hasKey && wasKeyEmpty &&
                    _state.value.connectionStatus == ConnectionStatus.Disconnected
                ) {
                    logger.d("✓ API ключ загружен из DataStore → авто-коннект")
                    handleConnect()
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  INTENT DISPATCHER
    // ════════════════════════════════════════════════════════════

    fun onIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.SubmitApiKey -> handleSubmitApiKey(intent.key)
            is VoiceIntent.Connect -> handleConnect()
            is VoiceIntent.Disconnect -> handleDisconnect()
            is VoiceIntent.ToggleMic -> handleToggleMic()
            is VoiceIntent.SendText -> handleSendText(intent.text)
            is VoiceIntent.SaveLog -> handleSaveLog()
        }
    }

    // ═══════════════════════════════════════════
    //  SESSION CONFIG — AppSettings → SessionConfig
    // ═══════════════════════════════════════════

    private fun buildSessionConfig(): SessionConfig {
        val profile = runCatching {
            enumValueOf<LatencyProfile>(cachedSettings.latencyProfile)
        }.getOrDefault(LatencyProfile.UltraLow)

        return SessionConfig(
            voiceId               = cachedSettings.voiceId,
            latencyProfile        = profile,
            autoActivityDetection = cachedSettings.enableServerVad
        )
    }

    // ═══════════════════════════════════════════
    //  HANDLERS
    // ═══════════════════════════════════════════

    private fun handleSubmitApiKey(key: String) {
        if (key.length < 20) {
            _effects.tryEmit(VoiceEffect.ShowToast(UiText.Plain("Ключ слишком короткий")))
            return
        }
        viewModelScope.launch {
            settingsStore.updateData { it.copy(apiKey = key) }
            logger.d("✓ API ключ сохранён (AES-256-GCM, Android Keystore)")
        }
    }

    private fun handleConnect() {
        if (cachedSettings.apiKey.isEmpty()) return
        val status = _state.value.connectionStatus
        if (status == ConnectionStatus.Connecting || status == ConnectionStatus.Ready) return

        _state.update { it.copy(connectionStatus = ConnectionStatus.Connecting) }
        viewModelScope.launch {
            liveClient.connect(cachedSettings.apiKey, buildSessionConfig())
        }
    }

    private fun handleDisconnect() {
        reconnectJob?.cancel()
        micJob?.cancel()
        viewModelScope.launch {
            audioEngine.stopCapture()
            liveClient.disconnect()
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.Disconnected,
                    isMicActive      = false,
                    isAiSpeaking     = false
                )
            }
        }
    }

    private fun handleToggleMic() {
        if (_state.value.isMicActive) {
            stopMic()
        } else {
            if (_state.value.connectionStatus == ConnectionStatus.Ready) startMic()
        }
    }

    private fun startMic() {
        _state.update { it.copy(isMicActive = true, connectionStatus = ConnectionStatus.Recording) }
        micJob = viewModelScope.launch {
            launch {
                audioEngine.micOutput.collect { chunk -> liveClient.sendAudio(chunk) }
            }
            audioEngine.startCapture()
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        micJob = null
        viewModelScope.launch {
            audioEngine.stopCapture()
            liveClient.sendTurnComplete()
            _state.update {
                it.copy(
                    isMicActive      = false,
                    connectionStatus = if (liveClient.isReady) ConnectionStatus.Ready
                                       else ConnectionStatus.Disconnected
                )
            }
        }
    }

    private fun handleSendText(text: String) {
        if (text.isBlank()) return
        liveClient.sendText(text)
        conversationStore.add(ConversationMessage.user(text))
        _state.update { it.copy(transcript = conversationStore.getAll()) }
    }

    private fun handleSaveLog() {
        _effects.tryEmit(VoiceEffect.SaveLogToFile(logger.getFullLog()))
    }

    private fun updateSetting(transform: AppSettings.() -> AppSettings) {
        viewModelScope.launch {
            settingsStore.updateData { it.transform() }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  GEMINI EVENTS
    // ════════════════════════════════════════════════════════════

    private fun observeGeminiEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is GeminiEvent.Connected -> {
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Negotiating) }
                    }

                    is GeminiEvent.SetupComplete -> {
                        reconnectAttempt = 0
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Ready) }
                        // Context restore after reconnect
                        val history = conversationStore.getAll()
                        if (history.isNotEmpty()) {
                            liveClient.restoreContext(history)
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

                    // ── Транскрипция: юзер → add(), модель → appendOrAdd() ──
                    is GeminiEvent.InputTranscript -> {
                        conversationStore.add(ConversationMessage.user(event.text))
                        _state.update { it.copy(transcript = conversationStore.getAll()) }
                    }
                    is GeminiEvent.OutputTranscript -> {
                        conversationStore.appendOrAdd(ConversationMessage.ROLE_MODEL, event.text)
                        _state.update { it.copy(transcript = conversationStore.getAll()) }
                    }
                    is GeminiEvent.ModelText -> {
                        conversationStore.appendOrAdd(ConversationMessage.ROLE_MODEL, event.text)
                        _state.update { it.copy(transcript = conversationStore.getAll()) }
                    }

                    is GeminiEvent.ToolCall -> {
                        handleToolCalls(event)
                    }

                    is GeminiEvent.SessionHandleUpdate -> { /* сохранено в liveClient */ }
                    is GeminiEvent.GoAway -> {
                        reconnectAttempt = 0
                    }

                    is GeminiEvent.Disconnected -> {
                        _state.update {
                            it.copy(connectionStatus = ConnectionStatus.Disconnected, isMicActive = false)
                        }
                        viewModelScope.launch { audioEngine.stopCapture() }
                        scheduleReconnect()
                    }

                    is GeminiEvent.ConnectionError -> {
                        _state.update {
                            it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                                isMicActive = false,
                                error = UiText.Plain(event.message)
                            )
                        }
                        viewModelScope.launch { audioEngine.stopCapture() }
                        scheduleReconnect()
                    }
                }

                // Обновляем лог-текст
                _state.update { it.copy(logText = logger.getDisplayLog()) }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  TOOL CALLING (синхронный)
    // ════════════════════════════════════════════════════════════

    private fun handleToolCalls(event: GeminiEvent.ToolCall) {
        val responses = event.calls.map { call ->
            val result = dispatchTool(call.name, call.args)
            logger.d("🔧 TOOL_RESULT: ${call.name} → $result")
            ToolResponse(call.name, call.id, result)
        }
        liveClient.sendToolResponse(responses)
    }

    private fun dispatchTool(name: String, args: Map<String, String>): String = when (name) {
        else -> {
            logger.w("Unknown tool: $name")
            """{"error":"Function '$name' not implemented"}"""
        }
    }

    // ════════════════════════════════════════════════════════════
    //  RECONNECT
    // ════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════
    //  RECONNECT (exponential backoff)
    // ═══════════════════════════════════════════

    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            logger.d("Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached")
            _effects.tryEmit(VoiceEffect.ShowToast(UiText.Plain("Connection lost")))
            return
        }
        val delayMs = RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempt)
        reconnectAttempt++
        logger.d("Reconnect #$reconnectAttempt in ${delayMs}ms")
        _state.update { it.copy(connectionStatus = ConnectionStatus.Reconnecting) }

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            handleConnect()
        }
    }

    // ═══════════════════════════════════════════
    //  AUDIO INIT / CLEANUP
    // ═══════════════════════════════════════════

    private fun initAudioPlayback() {
        viewModelScope.launch { audioEngine.initPlayback() }
    }

    // ════════════════════════════════════════════════════════════
    //  CLEANUP
    // ════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        reconnectJob?.cancel()
        micJob?.cancel()
        viewModelScope.launch {
            audioEngine.releaseAll()
            liveClient.disconnect()
        }
    }
}