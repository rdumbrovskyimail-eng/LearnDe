package com.codeextractor.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private val GeminiBg       = Color(0xFFFFFFFF)
private val GeminiText     = Color(0xFF1A1A1A)
private val GeminiSecondary = Color(0xFF5F6368)
private val GeminiAccent   = Color(0xFF1A73E8)
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

    Scaffold(containerColor = GeminiBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text("Настройки", fontSize = 28.sp, color = GeminiText, modifier = Modifier.padding(bottom = 4.dp))
            Text("Gemini 3.1 Flash Live", fontSize = 14.sp, color = GeminiSecondary, modifier = Modifier.padding(bottom = 20.dp))

            GeminiSection("Доступ (API)") {
                GeminiTextField(
                    value = s.apiKey,
                    onValueChange = { viewModel.update { copy(apiKey = it) } },
                    label = "Основной API Ключ",
                    placeholder = "AIza..."
                )
                Text("Ваш личный ключ от Google AI Studio для подключения к нейросети.", fontSize = 11.sp, color = GeminiSecondary)
                
                GeminiTextField(
                    value = s.apiKeyBackup,
                    onValueChange = { viewModel.update { copy(apiKeyBackup = it, autoRotateKeys = it.isNotEmpty()) } },
                    label = "Резервный API Ключ",
                    placeholder = "Опционально"
                )
                Text("Используется автоматически, если основной ключ исчерпал лимит запросов (ошибка 429).", fontSize = 11.sp, color = GeminiSecondary)
            }

            GeminiSection("Голос и аватар") {
                GeminiDropdown(
                    label = "Голос ассистента",
                    selected = s.voiceId,
                    options = listOf("Puck", "Charon", "Fenrir", "Orus", "Kore", "Aoede", "Leda", "Zephyr"),
                    displayNames = listOf("Puck ♂", "Charon ♂", "Fenrir ♂", "Orus ♂", "Kore ♀", "Aoede ♀", "Leda ♀", "Zephyr ♀"),
                    onSelected = { viewModel.update { copy(voiceId = it) } }
                )
                Text("Выбор голоса автоматически меняет пол 3D-аватара на экране общения.", fontSize = 11.sp, color = GeminiSecondary)

                GeminiDropdown(
                    label = "Уровень размышления (Latency)",
                    selected = s.latencyProfile,
                    options = LatencyProfile.entries.map { it.name },
                    displayNames = LatencyProfile.entries.map { it.displayName },
                    onSelected = { viewModel.update { copy(latencyProfile = it) } }
                )
                Text("Определяет, как долго ИИ думает перед ответом. UltraLow — для быстрых бесед, Reasoning — для сложных задач.", fontSize = 11.sp, color = GeminiSecondary)
            }

            GeminiSection("Генерация текста") {
                GeminiSlider("Креативность (Temperature)", s.temperature, 0f..2f, "%.2f") {
                    viewModel.update { copy(temperature = it) }
                }
                Text("0.0 — строгие и точные ответы. 2.0 — максимально креативные и непредсказуемые.", fontSize = 11.sp, color = GeminiSecondary)

                GeminiSlider("Длина ответа (Max Tokens)", s.maxOutputTokens.toFloat(), 256f..65536f, "%.0f") {
                    viewModel.update { copy(maxOutputTokens = it.toInt()) }
                }
                Text("Максимальное количество слов (токенов), которое ИИ может произнести за один раз.", fontSize = 11.sp, color = GeminiSecondary)
            }

            GeminiSection("Аудио и микрофон") {
                GeminiSwitch("Эхоподавление (AEC)", s.useAec, "Устраняет эхо, чтобы ИИ не слышал сам себя из динамика телефона.") {
                    viewModel.update { copy(useAec = it) }
                }
                GeminiSwitch("Серверный VAD", s.enableServerVad, "ИИ сам понимает, когда вы закончили говорить, и начинает отвечать.") {
                    viewModel.update { copy(enableServerVad = it) }
                }
            }

            GeminiSection("Сессия и Память") {
                GeminiSwitch("Восстановление сессии", s.enableSessionResumption, "Если интернет пропадет, диалог не сбросится, а продолжится с того же места.") {
                    viewModel.update { copy(enableSessionResumption = it) }
                }
                GeminiSwitch("Сжатие контекста", s.enableContextCompression, "Автоматически сжимает старые сообщения при долгих беседах для экономии токенов.") {
                    viewModel.update { copy(enableContextCompression = it) }
                }
            }

            GeminiSection("Инструменты") {
                GeminiSwitch("Поиск в Google", s.enableGoogleSearch, "Разрешает ИИ искать актуальную информацию в интернете для ответа на вопросы.") {
                    viewModel.update { copy(enableGoogleSearch = it) }
                }
            }

            GeminiSection("Системная инструкция") {
                GeminiTextField(
                    value = s.systemInstruction,
                    onValueChange = { viewModel.update { copy(systemInstruction = it) } },
                    label = "Промпт (Поведение ИИ)",
                    minLines = 4, maxLines = 8
                )
                Text("Базовые правила для ИИ. Укажите здесь, как он должен себя вести, на каком языке говорить и какой у него характер.", fontSize = 11.sp, color = GeminiSecondary)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onStartSession, // Просто переходим на экран голоса, микрофон запросим там!
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GeminiAccent),
                enabled = s.apiKey.length >= 20
            ) {
                Text("Перейти к общению", fontSize = 16.sp, color = Color.White)
            }

            TextButton(
                onClick = { viewModel.resetToDefaults() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("Сбросить настройки", color = GeminiError, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun GeminiSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GeminiAccent, modifier = Modifier.padding(bottom = 8.dp))
        Column(
            modifier = Modifier.fillMaxWidth().background(GeminiSurface, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
    }
}

@Composable
private fun GeminiSwitch(title: String, checked: Boolean, subtitle: String, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 15.sp, color = GeminiText)
            Text(subtitle, fontSize = 11.sp, color = GeminiSecondary, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = GeminiAccent))
    }
}

@Composable
private fun GeminiSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 15.sp, color = GeminiText)
            Text(String.format(format, value), fontSize = 13.sp, color = GeminiAccent, fontWeight = FontWeight.Medium)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = GeminiAccent, activeTrackColor = GeminiAccent))
    }
}

@Composable
private fun GeminiTextField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String = "", minLines: Int = 1, maxLines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label, color = GeminiSecondary) },
        placeholder = { Text(placeholder, color = Color(0xFFBDC1C6)) }, modifier = Modifier.fillMaxWidth(),
        minLines = minLines, maxLines = maxLines,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GeminiAccent, cursorColor = GeminiAccent)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeminiDropdown(label: String, selected: String, options: List<String>, displayNames: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val idx = options.indexOf(selected).coerceAtLeast(0)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = displayNames.getOrElse(idx) { selected }, onValueChange = {}, readOnly = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GeminiAccent)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Color.White)) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(text = { Text(displayNames.getOrElse(i) { option }) }, onClick = { onSelected(option); expanded = false })
            }
        }
    }
}
