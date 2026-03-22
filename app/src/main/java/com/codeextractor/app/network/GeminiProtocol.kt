package com.codeextractor.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object GeminiProtocol {
    val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

@Serializable
data class SetupMessage(val setup: SetupBody)

@Serializable
data class SetupBody(
    val model: String,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: SystemInstruction? = null,
    val tools: List<ToolDeclaration>? = null,
    val realtimeInputConfig: RealtimeInputConfig? = null
)

@Serializable
data class GenerationConfig(
    val responseModalities: List<String> = listOf("AUDIO"),
    val speechConfig: SpeechConfig? = null,
    val thinkingConfig: ThinkingConfig? = null
)

@Serializable
data class SpeechConfig(val voiceConfig: VoiceConfig)

@Serializable
data class VoiceConfig(val prebuiltVoiceConfig: PrebuiltVoice)

@Serializable
data class PrebuiltVoice(val voiceName: String)

@Serializable
data class ThinkingConfig(val thinkingBudget: Int)

@Serializable
data class SystemInstruction(val parts: List<Part>)

@Serializable
data class Part(val text: String)

@Serializable
data class RealtimeInputConfig(
    val automaticActivityDetection: AutomaticActivityDetection? = null,
    val activityHandling: String? = null
)

@Serializable
data class AutomaticActivityDetection(
    val disabled: Boolean = false,
    val startOfSpeechSensitivity: String? = null,
    val endOfSpeechSensitivity: String? = null,
    val prefixPaddingMs: Int? = null,
    val silenceDurationMs: Int? = null
)

@Serializable
data class ToolDeclaration(
    val functionDeclarations: List<FunctionDeclarationProto>
)

@Serializable
data class FunctionDeclarationProto(
    val name: String,
    val description: String,
    val parameters: ParametersProto? = null
)

@Serializable
data class ParametersProto(
    val type: String = "object",
    val properties: Map<String, PropertyProto>,
    val required: List<String> = emptyList()
)

@Serializable
data class PropertyProto(
    val type: String,
    val description: String
)

@Serializable
data class RealtimeInputMessage(
    val realtimeInput: RealtimeInputBody
)

@Serializable
data class RealtimeInputBody(
    val audio: AudioData? = null,
    val text: String? = null
)

@Serializable
data class AudioData(
    val data: String,
    val mimeType: String
)