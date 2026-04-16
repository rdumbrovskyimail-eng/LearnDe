// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА (НОВЫЙ ФАЙЛ)
// Путь: app/src/main/java/com/codeextractor/app/presentation/settings/SettingsScreen.kt
// Изменения:
//   + Все 34 настройки из AppSettings выведены в UI
//   + Русскоязычное описание под каждым пунктом
//   + Современный menuAnchor(MenuAnchorType) API — совместим с Compose BOM 2026.03.01
//   + Маскировка API ключей (eye-toggle)
//   + rememberSaveable для локального UI state
//   + Устранён потенциальный краш при повторном входе в экран
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.presentation.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeextractor.app.domain.model.LatencyProfile

private val GeminiBg        = Color(0xFFFFFFFF)
private val GeminiText      = Color(0xFF1A1A1A)
private val GeminiSecondary = Color(0xFF5F6368)
private val GeminiAccent    = Color(0xFF1A73E8)
private val GeminiSurface   = Color(0xFFF8F9FA)
private val GeminiError     = Color(0xFFD93025)

// ────────────────────────────────────────────────────────────
// СПИСКИ ВЫБОРА
// ────────────────────────────────────────────────────────────

/** Актуальный список моделей Gemini Live API на апрель 2026. */
private val AVAILABLE_MODELS = listOf(
    "models/gemini-live-2.5-flash-preview"          to "Gemini 2.5 Flash Live (стабильная)",
    "models/gemini-live-2.5-flash-native-audio"     to "Gemini 2.5 Flash Native Audio (production)",
    "models/gemini-3.1-flash-live-preview"          to "Gemini 3.1 Flash Live (preview)"
)

private val AVAILABLE_VOICES = listOf(
    "Puck"   to "Puck ♂ (энергичный)",
    "Charon" to "Charon ♂ (серьёзный)",
    "Fenrir" to "Fenrir ♂ (низкий)",
    "Orus"   to "Orus ♂ (дружелюбный)",
    "Kore"   to "Kore ♀ (мягкий)",
    "Aoede"  to "Aoede ♀ (тёплый)",
    "Leda"   to "Leda ♀ (живой)",
    "Zephyr" to "Zephyr ♀ (спокойный)"
)

private val AVAILABLE_LANGUAGES = listOf(
    ""      to "Автоопределение",
    "ru-RU" to "Русский",
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "de-DE" to "Deutsch",
    "es-ES" to "Español",
    "fr-FR" to "Français",
    "it-IT" to "Italiano",
    "pt-BR" to "Português (BR)",
    "ja-JP" to "日本語",
    "ko-KR" to "한국어",
    "zh-CN" to "中文 (简体)",
    "hi-IN" to "हिन्दी"
)

private val RESPONSE_MODALITIES = listOf(
    "AUDIO" to "AUDIO — голосовые ответы",
    "TEXT"  to "TEXT — только текст"
)

// ════════════════════════════════════════════════════════════
//  MAIN SCREEN
// ════════════════════════════════════════════════════════════

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
            Text("Gemini Live — полная конфигурация", fontSize = 14.sp, color = GeminiSecondary, modifier = Modifier.padding(bottom = 20.dp))

            // ── 1. ДОСТУП ──────────────────────────────────────
            GeminiSection("1. Доступ (API)") {
                SecureApiKeyField(
                    value = s.apiKey,
                    label = "Основной API ключ",
                    placeholder = "AIza…",
                    onValueChange = { viewModel.update { copy(apiKey = it) } }
                )
                Hint("Личный ключ Google AI Studio для подключения к Gemini. Без него приложение не работает. Получить бесплатно: aistudio.google.com → Get API key.")

                SecureApiKeyField(
                    value = s.apiKeyBackup,
                    label = "Резервный API ключ",
                    placeholder = "Опционально",
                    onValueChange = { viewModel.update { copy(apiKeyBackup = it, autoRotateKeys = it.isNotEmpty()) } }
                )
                Hint("Используется автоматически при ошибке лимита (429) на основном ключе. Удобно для бесплатного тарифа: два ключа от разных аккаунтов удваивают квоту.")

                GeminiSwitch(
                    title = "Авто-ротация ключей при 429",
                    checked = s.autoRotateKeys,
                    subtitle = "Переключаться на резервный ключ при исчерпании лимита основного.",
                    onCheckedChange = { viewModel.update { copy(autoRotateKeys = it) } }
                )
            }

            // ── 2. МОДЕЛЬ ─────────────────────────────────────
            GeminiSection("2. Модель ИИ") {
                GeminiDropdown(
                    label = "Модель",
                    selected = s.model,
                    options = AVAILABLE_MODELS.map { it.first },
                    displayNames = AVAILABLE_MODELS.map { it.second },
                    onSelected = { viewModel.update { copy(model = it) } }
                )
                Hint("«Gemini 2.5 Flash Live (стабильная)» — рекомендуется для начала. «3.1 Flash Live» — новее и умнее, но доступен не всем ключам и может давать ошибку подключения.")

                GeminiDropdown(
                    label = "Формат ответа (Modality)",
                    selected = s.responseModality,
                    options = RESPONSE_MODALITIES.map { it.first },
                    displayNames = RESPONSE_MODALITIES.map { it.second },
                    onSelected = { viewModel.update { copy(responseModality = it) } }
                )
                Hint("AUDIO — модель отвечает голосом (для голосового ассистента). TEXT — только текстом (без звука).")
            }

            // ── 3. ГЕНЕРАЦИЯ ──────────────────────────────────
            GeminiSection("3. Параметры генерации") {
                GeminiSlider("Креативность (Temperature)", s.temperature, 0f..2f, "%.2f") {
                    viewModel.update { copy(temperature = it) }
                }
                Hint("0.0 — строгие, предсказуемые ответы. 1.0 — сбалансировано. 2.0 — максимально креативно и непредсказуемо. Для диалога обычно 0.7–1.2.")

                GeminiSlider("Top-P (nucleus sampling)", s.topP, 0f..1f, "%.2f") {
                    viewModel.update { copy(topP = it) }
                }
                Hint("Ограничивает выбор слов по накопленной вероятности. 0.95 — стандарт. Меньше — более «консервативные» формулировки.")

                GeminiIntSlider("Top-K", s.topK, 0..100) {
                    viewModel.update { copy(topK = it) }
                }
                Hint("Число слов-кандидатов для следующего токена. 0 = выключено. 40 — сбалансированное значение.")

                GeminiIntSlider("Максимум токенов в ответе", s.maxOutputTokens, 256..65536, step = 256) {
                    viewModel.update { copy(maxOutputTokens = it) }
                }
                Hint("Потолок длины одного ответа модели. 8192 — достаточно для большинства диалогов. 65536 — максимум для длинных размышлений.")

                GeminiSlider("Presence penalty", s.presencePenalty, -2f..2f, "%.2f") {
                    viewModel.update { copy(presencePenalty = it) }
                }
                Hint("Штраф за повторение любых слов из контекста. Положительное — меньше повторов, отрицательное — больше.")

                GeminiSlider("Frequency penalty", s.frequencyPenalty, -2f..2f, "%.2f") {
                    viewModel.update { copy(frequencyPenalty = it) }
                }
                Hint("Штраф за частоту одного и того же слова. Аналогично presence penalty, но учитывает частоту повторений.")
            }

            // ── 4. ГОЛОС И ЯЗЫК ───────────────────────────────
            GeminiSection("4. Голос и язык") {
                GeminiDropdown(
                    label = "Голос ассистента",
                    selected = s.voiceId,
                    options = AVAILABLE_VOICES.map { it.first },
                    displayNames = AVAILABLE_VOICES.map { it.second },
                    onSelected = { viewModel.update { copy(voiceId = it) } }
                )
                Hint("Выбор голоса автоматически меняет пол 3D-аватара (♂/♀). У каждого голоса свой тембр.")

                GeminiDropdown(
                    label = "Язык ответа",
                    selected = s.languageCode,
                    options = AVAILABLE_LANGUAGES.map { it.first },
                    displayNames = AVAILABLE_LANGUAGES.map { it.second },
                    onSelected = { viewModel.update { copy(languageCode = it) } }
                )
                Hint("Принудительный язык TTS. «Автоопределение» — модель сама выберет язык по вашей речи. Русский — если акцент мешает распознаванию.")

                GeminiDropdown(
                    label = "Профиль размышления (Latency)",
                    selected = s.latencyProfile,
                    options = LatencyProfile.entries.map { it.name },
                    displayNames = LatencyProfile.entries.map { it.displayName },
                    onSelected = { viewModel.update { copy(latencyProfile = it) } }
                )
                Hint("UltraLow — мгновенные ответы без глубоких размышлений. Low — лёгкое размышление. Balanced — компромисс. Reasoning — модель думает долго, но отвечает умнее (для сложных задач).")
            }

            // ── 5. АУДИО ──────────────────────────────────────
            GeminiSection("5. Аудио и микрофон") {
                GeminiSwitch(
                    title = "Эхоподавление (AEC)",
                    checked = s.useAec,
                    subtitle = "Устраняет эхо от динамика телефона. Выключайте только если слышите искажения в наушниках.",
                    onCheckedChange = { viewModel.update { copy(useAec = it) } }
                )

                GeminiSwitch(
                    title = "Посылать audioStreamEnd при паузе",
                    checked = s.sendAudioStreamEnd,
                    subtitle = "При нажатии «Стоп» явно сигнализировать модели о конце речи. Ускоряет ответ.",
                    onCheckedChange = { viewModel.update { copy(sendAudioStreamEnd = it) } }
                )

                GeminiIntSlider("Jitter-буфер (чанков)", s.jitterPreBufferChunks, 1..10) {
                    viewModel.update { copy(jitterPreBufferChunks = it) }
                }
                Hint("Накопление аудио перед воспроизведением для плавности. 3 — оптимум. Больше — стабильнее на слабой сети, но выше задержка.")

                GeminiLongSlider("Таймаут jitter (мс)", s.jitterTimeoutMs, 50L..500L) {
                    viewModel.update { copy(jitterTimeoutMs = it) }
                }
                Hint("Максимальное ожидание следующего чанка. При превышении — начинается воспроизведение того, что есть.")

                GeminiIntSlider("Размер очереди playback", s.playbackQueueCapacity, 64..512, step = 32) {
                    viewModel.update { copy(playbackQueueCapacity = it) }
                }
                Hint("Внутренний буфер аудио в памяти. 256 достаточно для любого сценария.")
            }

            // ── 6. VAD ────────────────────────────────────────
            GeminiSection("6. Определение речи (VAD)") {
                GeminiSwitch(
                    title = "Серверный VAD",
                    checked = s.enableServerVad,
                    subtitle = "Модель сама определяет, когда вы закончили говорить. Выключение — ручное управление через кнопку «Стоп».",
                    onCheckedChange = { viewModel.update { copy(enableServerVad = it) } }
                )

                GeminiSlider("Чувствительность начала речи", s.vadStartOfSpeechSensitivity, 0f..1f, "%.2f") {
                    viewModel.update { copy(vadStartOfSpeechSensitivity = it) }
                }
                Hint("Насколько громко нужно начать говорить, чтобы модель начала слушать. 0.5 — стандарт. Выше — нужна более громкая речь.")

                GeminiSlider("Чувствительность конца речи", s.vadEndOfSpeechSensitivity, 0f..1f, "%.2f") {
                    viewModel.update { copy(vadEndOfSpeechSensitivity = it) }
                }
                Hint("Насколько чётким должен быть конец фразы. Выше — модель ждёт более явной паузы.")

                GeminiIntSlider("Таймаут тишины (мс)", s.vadSilenceTimeoutMs, 0..5000, step = 100) {
                    viewModel.update { copy(vadSilenceTimeoutMs = it) }
                }
                Hint("Сколько миллисекунд тишины считается концом вашей реплики. 0 — дефолт сервера (~800 мс). Больше — даёт вам время подумать.")
            }

            // ── 7. ТРАНСКРИПЦИЯ ───────────────────────────────
            GeminiSection("7. Транскрипция") {
                GeminiSwitch(
                    title = "Транскрипция вашей речи",
                    checked = s.inputTranscription,
                    subtitle = "Показывать текст того, что вы говорите, в истории диалога.",
                    onCheckedChange = { viewModel.update { copy(inputTranscription = it) } }
                )
                GeminiSwitch(
                    title = "Транскрипция речи модели",
                    checked = s.outputTranscription,
                    subtitle = "Показывать текст ответа ИИ (помимо голоса).",
                    onCheckedChange = { viewModel.update { copy(outputTranscription = it) } }
                )
            }

            // ── 8. СЕССИЯ ─────────────────────────────────────
            GeminiSection("8. Сессия и память") {
                GeminiSwitch(
                    title = "Восстановление сессии",
                    checked = s.enableSessionResumption,
                    subtitle = "При потере интернета диалог возобновится с того же места, а не сбросится.",
                    onCheckedChange = { viewModel.update { copy(enableSessionResumption = it) } }
                )
                GeminiSwitch(
                    title = "Прозрачное восстановление",
                    checked = s.transparentResumption,
                    subtitle = "При reconnect автоматически подтягивать уже отправленные сообщения, не теряя их.",
                    onCheckedChange = { viewModel.update { copy(transparentResumption = it) } }
                )
                GeminiSwitch(
                    title = "Сжатие контекста",
                    checked = s.enableContextCompression,
                    subtitle = "Автоматически сжимать старые сообщения в долгих диалогах — экономит токены и продлевает сессию.",
                    onCheckedChange = { viewModel.update { copy(enableContextCompression = it) } }
                )
                GeminiIntSlider("Порог сжатия (токенов)", s.compressionTriggerTokens, 0..128000, step = 1024) {
                    viewModel.update { copy(compressionTriggerTokens = it) }
                }
                Hint("Начинать сжатие после этого числа токенов в контексте. 0 — дефолт сервера.")
            }

            // ── 9. РЕКОННЕКТ ──────────────────────────────────
            GeminiSection("9. Переподключение") {
                GeminiIntSlider("Максимум попыток", s.maxReconnectAttempts, 1..20) {
                    viewModel.update { copy(maxReconnectAttempts = it) }
                }
                Hint("Сколько раз подряд пробовать восстановить соединение при обрыве. После лимита показывается ошибка.")

                GeminiLongSlider("Базовая задержка (мс)", s.reconnectBaseDelayMs, 500L..10_000L, step = 500L) {
                    viewModel.update { copy(reconnectBaseDelayMs = it) }
                }
                Hint("Начальная пауза между попытками. Каждая следующая попытка удваивает паузу (exponential backoff).")

                GeminiLongSlider("Максимум задержки (мс)", s.reconnectMaxDelayMs, 5_000L..120_000L, step = 5000L) {
                    viewModel.update { copy(reconnectMaxDelayMs = it) }
                }
                Hint("Потолок задержки — пауза не вырастет выше этого значения даже после многих попыток.")
            }

            // ── 10. ИНСТРУМЕНТЫ ──────────────────────────────
            GeminiSection("10. Инструменты") {
                GeminiSwitch(
                    title = "Поиск в Google",
                    checked = s.enableGoogleSearch,
                    subtitle = "Разрешить модели искать актуальную информацию в интернете для ответов на вопросы о новостях, ценах, фактах.",
                    onCheckedChange = { viewModel.update { copy(enableGoogleSearch = it) } }
                )
            }

            // ── 11. СИСТЕМНАЯ ИНСТРУКЦИЯ ─────────────────────
            GeminiSection("11. Системная инструкция") {
                GeminiTextField(
                    value = s.systemInstruction,
                    onValueChange = { viewModel.update { copy(systemInstruction = it) } },
                    label = "Поведение и характер ИИ",
                    minLines = 4, maxLines = 10
                )
                Hint("Базовые правила для ИИ: как общаться, на каком языке, какой характер. Пример: «Отвечай только на русском, кратко, дружелюбно».")
            }

            // ── 12. DEBUG ────────────────────────────────────
            GeminiSection("12. Диагностика") {
                GeminiSwitch(
                    title = "Debug-лог на экране",
                    checked = s.showDebugLog,
                    subtitle = "Показывать техлог поверх UI. Для разработчиков.",
                    onCheckedChange = { viewModel.update { copy(showDebugLog = it) } }
                )
                GeminiSwitch(
                    title = "Логировать WebSocket-фреймы",
                    checked = s.logRawWebSocketFrames,
                    subtitle = "Писать в лог сырые сообщения сервера. ОСТОРОЖНО: много данных, быстро съедает память.",
                    onCheckedChange = { viewModel.update { copy(logRawWebSocketFrames = it) } }
                )
                GeminiSwitch(
                    title = "Счётчик токенов",
                    checked = s.showUsageMetadata,
                    subtitle = "Показывать количество потраченных токенов на экране общения.",
                    onCheckedChange = { viewModel.update { copy(showUsageMetadata = it) } }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onStartSession,
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
                Text("Сбросить настройки к дефолту", color = GeminiError, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

// ════════════════════════════════════════════════════════════
//  REUSABLE COMPONENTS
// ════════════════════════════════════════════════════════════

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
private fun Hint(text: String) {
    Text(text, fontSize = 11.sp, color = GeminiSecondary, lineHeight = 14.sp)
}

@Composable
private fun GeminiSwitch(title: String, checked: Boolean, subtitle: String, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 15.sp, color = GeminiText)
            Text(subtitle, fontSize = 11.sp, color = GeminiSecondary, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = GeminiAccent)
        )
    }
}

@Composable
private fun GeminiSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 15.sp, color = GeminiText)
            Text(String.format(java.util.Locale.US, format, value), fontSize = 13.sp, color = GeminiAccent, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = GeminiAccent, activeTrackColor = GeminiAccent)
        )
    }
}

@Composable
private fun GeminiIntSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 15.sp, color = GeminiText)
            Text(value.toString(), fontSize = 13.sp, color = GeminiAccent, fontWeight = FontWeight.Medium)
        }
        val coerced = value.coerceIn(range.first, range.last).toFloat()
        Slider(
            value = coerced,
            onValueChange = { new ->
                val rounded = ((new / step).toInt() * step).coerceIn(range.first, range.last)
                onValueChange(rounded)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = GeminiAccent, activeTrackColor = GeminiAccent)
        )
    }
}

@Composable
private fun GeminiLongSlider(
    label: String,
    value: Long,
    range: LongRange,
    step: Long = 1L,
    onValueChange: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 15.sp, color = GeminiText)
            Text(value.toString(), fontSize = 13.sp, color = GeminiAccent, fontWeight = FontWeight.Medium)
        }
        val coerced = value.coerceIn(range.first, range.last).toFloat()
        Slider(
            value = coerced,
            onValueChange = { new ->
                val rounded = ((new.toLong() / step) * step).coerceIn(range.first, range.last)
                onValueChange(rounded)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = GeminiAccent, activeTrackColor = GeminiAccent)
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
    maxLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = GeminiSecondary) },
        placeholder = { Text(placeholder, color = Color(0xFFBDC1C6)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = GeminiAccent,
            cursorColor = GeminiAccent
        )
    )
}

/** API-key поле со скрытием/показом через иконку-глаз. */
@Composable
private fun SecureApiKeyField(
    value: String,
    label: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    GeminiTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (visible) "Скрыть ключ" else "Показать ключ",
                    tint = GeminiSecondary
                )
            }
        }
    )
}

/**
 * Dropdown с современным API (menuAnchor(MenuAnchorType)) — совместим с Compose BOM 2026.03.01.
 * Защищён от некорректного `selected` (не входящего в options).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeminiDropdown(
    label: String,
    selected: String,
    options: List<String>,
    displayNames: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val safeOptions = if (options.isEmpty()) listOf(selected) else options
    val safeNames = if (displayNames.size == safeOptions.size) displayNames else safeOptions
    val idx = safeOptions.indexOf(selected).takeIf { it >= 0 } ?: 0

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = safeNames.getOrElse(idx) { selected.ifBlank { "—" } },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GeminiAccent)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White)
        ) {
            safeOptions.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(safeNames.getOrElse(i) { option }) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
