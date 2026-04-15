package com.codeextractor.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeextractor.app.domain.model.LatencyProfile
import com.codeextractor.app.presentation.navigation.VoiceGender

// ── Gemini-стиль: цвета ─────────────────────────────────────────────────────
private val GeminiBg       = Color(0xFFFFFFFF)
private val GeminiText     = Color(0xFF1A1A1A)
private val GeminiSecondary = Color(0xFF5F6368)
private val GeminiAccent   = Color(0xFF1A73E8) // Google Blue
private val GeminiDivider  = Color(0xFFE8EAED)
private val GeminiSurface  = Color(0xFFF8F9FA)
private val GeminiError    = Color(0xFFD93025)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onStartSession: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = GeminiBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(GeminiBg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── Header ──
            Text(
                "CopyM Settings",
                fontSize = 28.sp,
                fontWeight = FontWeight.Normal,
                color = GeminiText,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "Gemini 3.1 Flash Live • ${VoiceGender.avatarIndexForVoice(s.voiceId).let { if (it == 1) "♂ Мужской" else "♀ Женский" }} аватар",
                fontSize = 14.sp,
                color = GeminiSecondary,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // ── API Key ──
            GeminiSection("API") {
                GeminiTextField(
                    value = s.apiKey,
                    onValueChange = { viewModel.update { copy(apiKey = it) } },
                    label = "API Key",
                    placeholder = "AIza..."
                )
                GeminiTextField(
                    value = s.apiKeyBackup,
                    onValueChange = { viewModel.update { copy(apiKeyBackup = it, autoRotateKeys = it.isNotEmpty()) } },
                    label = "Backup API Key (ротация при 429)",
                    placeholder = "Опционально"
                )
            }

            // ══════════════════════════════════════════════════
            //  VOICE — переключение голоса меняет аватар!
            // ══════════════════════════════════════════════════
            GeminiSection("Голос и аватар") {
                GeminiDropdown(
                    label = "Голос (♂ мужской / ♀ женский)",
                    selected = s.voiceId,
                    options = listOf("Puck", "Charon", "Fenrir", "Orus", "Kore", "Aoede", "Leda", "Zephyr"),
                    displayNames = listOf("Puck ♂", "Charon ♂", "Fenrir ♂", "Orus ♂", "Kore ♀", "Aoede ♀", "Leda ♀", "Zephyr ♀"),
                    onSelected = { viewModel.update { copy(voiceId = it) } }
                )

                GeminiDropdown(
                    label = "Мышление (thinkingLevel)",
                    selected = s.latencyProfile,
                    options = LatencyProfile.entries.map { it.name },
                    displayNames = LatencyProfile.entries.map { it.displayName },
                    onSelected = { viewModel.update { copy(latencyProfile = it) } }
                )

                GeminiTextField(
                    value = s.languageCode,
                    onValueChange = { viewModel.update { copy(languageCode = it) } },
                    label = "Язык (BCP-47)",
                    placeholder = "ru-RU (пусто = авто)"
                )
            }

            // ══════════════════════════════════════════════════
            //  GENERATION
            // ══════════════════════════════════════════════════
            GeminiSection("Генерация") {
                GeminiSlider("Temperature", s.temperature, 0f..2f, "%.2f") {
                    viewModel.update { copy(temperature = it) }
                }
                GeminiSlider("Top-P", s.topP, 0f..1f, "%.2f") {
                    viewModel.update { copy(topP = it) }
                }
                GeminiSlider("Top-K", s.topK.toFloat(), 0f..100f, "%.0f") {
                    viewModel.update { copy(topK = it.toInt()) }
                }
                GeminiSlider("Max Output Tokens", s.maxOutputTokens.toFloat(), 256f..65536f, "%.0f") {
                    viewModel.update { copy(maxOutputTokens = it.toInt()) }
                }
            }

            // ══════════════════════════════════════════════════
            //  AUDIO & VAD
            // ══════════════════════════════════════════════════
            GeminiSection("Аудио") {
                GeminiSwitch("Эхоподавление (AEC)", s.useAec) {
                    viewModel.update { copy(useAec = it) }
                }
                GeminiSwitch("Серверный VAD", s.enableServerVad) {
                    viewModel.update { copy(enableServerVad = it) }
                }
                GeminiSwitch("audioStreamEnd при паузе", s.sendAudioStreamEnd) {
                    viewModel.update { copy(sendAudioStreamEnd = it) }
                }
                GeminiSlider("Jitter Pre-Buffer", s.jitterPreBufferChunks.toFloat(), 1f..10f, "%.0f чанков") {
                    viewModel.update { copy(jitterPreBufferChunks = it.toInt()) }
                }
            }

            // ══════════════════════════════════════════════════
            //  SESSION
            // ══════════════════════════════════════════════════
            GeminiSection("Сессия") {
                GeminiSwitch("Session Resumption", s.enableSessionResumption, "Восстановление при обрыве") {
                    viewModel.update { copy(enableSessionResumption = it) }
                }
                GeminiSwitch("Context Compression", s.enableContextCompression, "Sliding window (>15 мин)") {
                    viewModel.update { copy(enableContextCompression = it) }
                }
                GeminiSlider("Max Reconnect", s.maxReconnectAttempts.toFloat(), 1f..20f, "%.0f") {
                    viewModel.update { copy(maxReconnectAttempts = it.toInt()) }
                }
            }

            // ══════════════════════════════════════════════════
            //  TOOLS
            // ══════════════════════════════════════════════════
            GeminiSection("Инструменты") {
                GeminiSwitch("Google Search Grounding", s.enableGoogleSearch, "ИИ может искать в интернете") {
                    viewModel.update { copy(enableGoogleSearch = it) }
                }
            }

            // ══════════════════════════════════════════════════
            //  TRANSCRIPTION
            // ══════════════════════════════════════════════════
            GeminiSection("Транскрипция") {
                GeminiSwitch("Речь пользователя", s.inputTranscription) {
                    viewModel.update { copy(inputTranscription = it) }
                }
                GeminiSwitch("Ответ модели", s.outputTranscription) {
                    viewModel.update { copy(outputTranscription = it) }
                }
            }

            // ══════════════════════════════════════════════════
            //  SYSTEM PROMPT
            // ══════════════════════════════════════════════════
            GeminiSection("Системная инструкция") {
                GeminiTextField(
                    value = s.systemInstruction,
                    onValueChange = { viewModel.update { copy(systemInstruction = it) } },
                    label = "System Prompt",
                    placeholder = "Ты русскоязычный ассистент...",
                    minLines = 4,
                    maxLines = 10
                )
            }

            // ══════════════════════════════════════════════════
            //  DEBUG
            // ══════════════════════════════════════════════════
            GeminiSection("Debug") {
                GeminiSwitch("Debug Log", s.showDebugLog) {
                    viewModel.update { copy(showDebugLog = it) }
                }
                GeminiSwitch("Raw WS Frames", s.logRawWebSocketFrames, "Осторожно — много данных") {
                    viewModel.update { copy(logRawWebSocketFrames = it) }
                }
                GeminiSwitch("Usage Metadata", s.showUsageMetadata, "Счётчик токенов") {
                    viewModel.update { copy(showUsageMetadata = it) }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Start Session ──
            Button(
                onClick = onStartSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GeminiAccent
                ),
                enabled = s.apiKey.length >= 20
            ) {
                Text("Начать сессию", fontSize = 16.sp, color = Color.White)
            }

            // ── Reset ──
            TextButton(
                onClick = { viewModel.resetToDefaults() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Сбросить к дефолту", color = GeminiError, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  GEMINI-STYLED COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun GeminiSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = GeminiAccent,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(GeminiSurface, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun GeminiSwitch(
    title: String,
    checked: Boolean,
    subtitle: String = "",
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = GeminiText)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 12.sp, color = GeminiSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = GeminiAccent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFDADCE0)
            )
        )
    }
}

@Composable
private fun GeminiSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String = "%.1f",
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 15.sp, color = GeminiText)
            Text(String.format(format, value), fontSize = 13.sp, color = GeminiAccent, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = GeminiAccent,
                activeTrackColor = GeminiAccent,
                inactiveTrackColor = Color(0xFFDADCE0)
            )
        )
    }
}

@Composable
private fun GeminiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    minLines: Int = 1,
    maxLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = GeminiSecondary) },
        placeholder = { Text(placeholder, color = Color(0xFFBDC1C6)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        maxLines = maxLines,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = GeminiText,
            unfocusedTextColor = GeminiText,
            cursorColor = GeminiAccent,
            focusedBorderColor = GeminiAccent,
            unfocusedBorderColor = GeminiDivider
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeminiDropdown(
    label: String,
    selected: String,
    options: List<String>,
    displayNames: List<String> = options,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val idx = options.indexOf(selected).coerceAtLeast(0)

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = displayNames.getOrElse(idx) { selected },
            onValueChange = {},
            readOnly = true,
            label = { Text(label, color = GeminiSecondary) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = GeminiText,
                unfocusedTextColor = GeminiText,
                focusedBorderColor = GeminiAccent,
                unfocusedBorderColor = GeminiDivider
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White)
        ) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(displayNames.getOrElse(i) { option }, color = GeminiText) },
                    onClick = { onSelected(option); expanded = false }
                )
            }
        }
    }
}
