package com.learnde.app.presentation.client

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.ConnectionOrchestrator
import com.learnde.app.domain.LiveClient
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.domain.model.LatencyProfile
import com.learnde.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ClientState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isMicActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val transcript: List<ConversationMessage> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class ClientViewModel @Inject constructor(
    private val liveClient: LiveClient,
    private val audioEngine: AudioEngine,
    private val settingsStore: DataStore<AppSettings>,
    private val orchestrator: ConnectionOrchestrator,
    private val logger: AppLogger
) : ViewModel() {

    private val _state = MutableStateFlow(ClientState())
    val state = _state.asStateFlow()

    private var micJob: Job? = null

    init {
        viewModelScope.launch { audioEngine.initPlayback() }
        
        orchestrator.onPauseAudio = { stopMic() }
        orchestrator.onResumeAudio = { startMic() }
        orchestrator.onPermanentFailure = { msg ->
            _state.update { it.copy(error = msg, isConnecting = false, isConnected = false) }
        }

        observeEvents()
    }

    private fun observeEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                orchestrator.onEvent(event)
                when (event) {
                    is GeminiEvent.SetupComplete -> {
                        _state.update { it.copy(isConnected = true, isConnecting = false) }
                        startMic()
                    }
                    is GeminiEvent.Disconnected -> {
                        _state.update { it.copy(isConnected = false, isConnecting = false) }
                        stopMic()
                    }
                    is GeminiEvent.ConnectionError -> {
                        _state.update { it.copy(error = event.message, isConnecting = false, isConnected = false) }
                        stopMic()
                    }
                    is GeminiEvent.AudioChunk -> {
                        _state.update { it.copy(isAiSpeaking = true) }
                        audioEngine.enqueuePlayback(event.pcmData)
                    }
                    is GeminiEvent.TurnComplete -> {
                        _state.update { it.copy(isAiSpeaking = false) }
                        audioEngine.onTurnComplete()
                    }
                    is GeminiEvent.Interrupted -> {
                        _state.update { it.copy(isAiSpeaking = false) }
                        audioEngine.flushPlayback()
                    }
                    is GeminiEvent.InputTranscript -> addMessage(ConversationMessage.user(event.text))
                    is GeminiEvent.OutputTranscript -> addMessage(ConversationMessage.model(event.text))
                    else -> {}
                }
            }
        }
    }

    private fun addMessage(msg: ConversationMessage) {
        _state.update { it.copy(transcript = (it.transcript + msg).takeLast(50)) }
    }

    fun toggleConnection() {
        viewModelScope.launch {
            if (_state.value.isConnected || _state.value.isConnecting) {
                orchestrator.stop()
                stopMic()
            } else {
                _state.update { it.copy(isConnecting = true, error = null, transcript = emptyList()) }
                val settings = settingsStore.data.first()
                if (settings.apiKey.isBlank()) {
                    _state.update { it.copy(error = "API ключ не задан в настройках", isConnecting = false) }
                    return@launch
                }
                
                val profile = runCatching { enumValueOf<LatencyProfile>(settings.latencyProfile) }
                    .getOrDefault(LatencyProfile.UltraLow)

                val config = SessionConfig(
                    model = settings.model,
                    voiceId = settings.voiceId,
                    systemInstruction = settings.systemInstruction,
                    temperature = settings.temperature,
                    latencyProfile = profile,
                    inputTranscription = settings.inputTranscription,
                    outputTranscription = settings.outputTranscription
                )
                orchestrator.start(
                    scope = viewModelScope,
                    apiKey = settings.apiKey,
                    config = config,
                    maxAttempts = settings.maxReconnectAttempts,
                    baseDelayMs = settings.reconnectBaseDelayMs,
                    maxDelayMs = settings.reconnectMaxDelayMs,
                    logRaw = settings.logRawWebSocketFrames
                )
            }
        }
    }

    fun toggleMic() {
        if (_state.value.isMicActive) stopMic() else startMic()
    }

    private fun startMic() {
        if (_state.value.isMicActive) return
        _state.update { it.copy(isMicActive = true) }
        micJob = viewModelScope.launch {
            audioEngine.startCapture()
            audioEngine.micOutput.collect { chunk ->
                if (System.currentTimeMillis() > audioEngine.playbackAudibleUntilMs) {
                    liveClient.sendAudio(chunk)
                }
            }
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        micJob = null
        viewModelScope.launch {
            audioEngine.stopCapture()
            if (liveClient.isReady) {
                liveClient.sendAudioStreamEnd()
            }
            _state.update { it.copy(isMicActive = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            orchestrator.stop()
            audioEngine.releaseAll()
        }
    }
}