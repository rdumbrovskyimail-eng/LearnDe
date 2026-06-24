package com.learnde.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.data.settings.ThemeMode
import com.learnde.app.domain.model.LatencyProfile

private val AVAILABLE_MODELS = listOf("models/gemini-3.1-flash-live-preview" to "Gemini 3.1 Flash Live")
private val AVAILABLE_VOICES = listOf("Puck" to "Puck ♂", "Charon" to "Charon ♂", "Fenrir" to "Fenrir ♂", "Orus" to "Orus ♂", "Kore" to "Kore ♀", "Aoede" to "Aoede ♀", "Leda" to "Leda ♀", "Zephyr" to "Zephyr ♀")
private val RESPONSE_MODALITIES = listOf("AUDIO" to "AUDIO — голос", "TEXT" to "TEXT — только текст")
private val THEME_MODES = listOf(ThemeMode.AUTO to "По системе", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    DisposableEffect(Unit) { onDispose { viewModel.flushPendingSave() } }
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text("Настройки", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 4.dp))
            Text("Gemini 3.1 Flash Live", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))

            GeminiSection("1. Доступ (API)") {
                SecureApiKeyField(
                    value = s.apiKey,
                    label = "API ключ",
                    placeholder = "AIza…",
                    onValueChange = { viewModel.update { copy(apiKey = it) } }
                )
                Hint("Ключ Google AI Studio. Без него приложение не работает.")
            }

            GeminiSection("2. Модель ИИ") {
                GeminiDropdown("Модель", s.model, AVAILABLE_MODELS.map { it.first }, AVAILABLE_MODELS.map { it.second }) { viewModel.update { copy(model = it) } }
                GeminiDropdown("Формат ответа", s.responseModality, RESPONSE_MODALITIES.map { it.first }, RESPONSE_MODALITIES.map { it.second }) { viewModel.update { copy(responseModality = it) } }
            }

            GeminiSection("3. Параметры генерации") {
                GeminiSlider("Креативность", s.temperature, 0f..2f, "%.2f") { viewModel.update { copy(temperature = it) } }
                GeminiSlider("Top-P", s.topP, 0f..1f, "%.2f") { viewModel.update { copy(topP = it) } }
                GeminiIntSlider("Top-K", s.topK, 0..100) { viewModel.update { copy(topK = it) } }
                GeminiIntSlider("Max tokens", s.maxOutputTokens, 256..65536, 256) { viewModel.update { copy(maxOutputTokens = it) } }
            }

            GeminiSection("4. Голос и язык") {
                GeminiDropdown("Голос", s.voiceId, AVAILABLE_VOICES.map { it.first }, AVAILABLE_VOICES.map { it.second }) { viewModel.update { copy(voiceId = it) } }
                GeminiDropdown("Профиль размышления", s.latencyProfile, LatencyProfile.entries.map { it.name }, LatencyProfile.entries.map { it.displayName }) { viewModel.update { copy(latencyProfile = it) } }
            }

            GeminiSection("5. Аудио и микрофон") {
                GeminiIntSlider("Громкость (%)", s.playbackVolume, 0..100, 5) { viewModel.update { copy(playbackVolume = it) } }
                GeminiIntSlider("Усиление микрофона (%)", s.micGain, 50..200, 10) { viewModel.update { copy(micGain = it) } }
                GeminiSwitch("Громкоговоритель", s.forceSpeakerOutput, "Звук через основной динамик") { viewModel.update { copy(forceSpeakerOutput = it) } }
                GeminiSwitch("Эхоподавление (AEC)", s.useAec, "Устраняет эхо") { viewModel.update { copy(useAec = it) } }
                GeminiSwitch("audioStreamEnd при паузе", s.sendAudioStreamEnd, "Ускоряет ответ") { viewModel.update { copy(sendAudioStreamEnd = it) } }
            }

            GeminiSection("6. Определение речи (VAD)") {
                GeminiSwitch("Серверный VAD", s.enableServerVad, "Модель сама определяет конец реплики") { viewModel.update { copy(enableServerVad = it) } }
                GeminiSlider("Чувствительность начала", s.vadStartOfSpeechSensitivity, 0f..1f, "%.2f") { viewModel.update { copy(vadStartOfSpeechSensitivity = it) } }
                GeminiSlider("Чувствительность конца", s.vadEndOfSpeechSensitivity, 0f..1f, "%.2f") { viewModel.update { copy(vadEndOfSpeechSensitivity = it) } }
                GeminiIntSlider("Таймаут тишины (мс)", s.vadSilenceTimeoutMs, 0..5000, 100) { viewModel.update { copy(vadSilenceTimeoutMs = it) } }
            }

            GeminiSection("7. Транскрипция") {
                GeminiSwitch("Транскрипция вашей речи", s.inputTranscription, "Показывать ваш текст") { viewModel.update { copy(inputTranscription = it) } }
                GeminiSwitch("Транскрипция речи модели", s.outputTranscription, "Показывать текст ИИ") { viewModel.update { copy(outputTranscription = it) } }
            }

            GeminiSection("8. Сессия и память") {
                GeminiSwitch("Восстановление сессии", s.enableSessionResumption, "При обрыве сети") { viewModel.update { copy(enableSessionResumption = it) } }
            }

            GeminiSection("9. Тема оформления") {
                GeminiDropdown("Тема", s.themeMode.name, THEME_MODES.map { it.first.name }, THEME_MODES.map { it.second }) { name ->
                    val mode = runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.AUTO)
                    viewModel.update { copy(themeMode = mode) }
                }
            }

            GeminiSection("10. Системная инструкция") {
                GeminiTextField(s.systemInstruction, { viewModel.update { copy(systemInstruction = it) } }, "Поведение ИИ", minLines = 4, maxLines = 10)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Text("Сохранить и выйти", fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimary)
            }

            TextButton(
                onClick = { viewModel.resetToDefaults() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("Сбросить все настройки", color = error, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun GeminiSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
}

@Composable
private fun GeminiSwitch(title: String, checked: Boolean, subtitle: String, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun GeminiSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(String.format(java.util.Locale.US, format, value), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun GeminiIntSlider(label: String, value: Int, range: IntRange, step: Int = 1, onValueChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(value.toString(), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
        val coerced = value.coerceIn(range.first, range.last).toFloat()
        Slider(value = coerced, onValueChange = { new -> val rounded = ((new / step).toInt() * step).coerceIn(range.first, range.last); onValueChange(rounded) }, valueRange = range.first.toFloat()..range.last.toFloat(), colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun GeminiTextField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String = "", minLines: Int = 1, maxLines: Int = 1, visualTransformation: VisualTransformation = VisualTransformation.None, trailingIcon: @Composable (() -> Unit)? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) }, placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }, modifier = Modifier.fillMaxWidth(), minLines = minLines, maxLines = maxLines, visualTransformation = visualTransformation, trailingIcon = trailingIcon, keyboardOptions = keyboardOptions, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary))
}

@Composable
private fun SecureApiKeyField(value: String, label: String, placeholder: String, onValueChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    GeminiTextField(value = value, onValueChange = onValueChange, label = label, placeholder = placeholder, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(imageVector = if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeminiDropdown(label: String, selected: String, options: List<String>, displayNames: List<String>, onSelected: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val safeOptions = if (options.isEmpty()) listOf(selected) else options
    val safeNames = if (displayNames.size == safeOptions.size) displayNames else safeOptions
    val idx = safeOptions.indexOf(selected).takeIf { it >= 0 } ?: 0

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(value = safeNames.getOrElse(idx) { selected.ifBlank { "—" } }, onValueChange = {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary))
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            safeOptions.forEachIndexed { i, option -> DropdownMenuItem(text = { Text(safeNames.getOrElse(i) { option }) }, onClick = { onSelected(option); expanded = false }) }
        }
    }
}