package com.codeextractor.app.data.settings

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val apiKey: String = "",
    val voiceId: String = "Aoede",
    val latencyProfile: String = "UltraLow",
    val useAec: Boolean = true,
    val enableServerVad: Boolean = true,
    val showDebugLog: Boolean = false
)