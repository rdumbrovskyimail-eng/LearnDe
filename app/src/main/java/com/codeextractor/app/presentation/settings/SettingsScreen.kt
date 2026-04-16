// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/presentation/settings/SettingsScreen.kt
// Изменения:
//   + Новый минималистический Gemini-дизайн (поддерживает light/dark)
//   + Все цвета через MaterialTheme.colorScheme (никаких хардкод Color(0xFF..))
//   + Только модель 3.1 Flash Live, остальные удалены
//   + 5 новых секций: Тема, Громкость, Сцена (+ PNG фон), Чат, Функции
//   + Импорт MenuAnchorType (фикс ClassNotFoundException)
//   + Защита от повторного входа (был краш)
//   + rememberSaveable везде
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.presentation.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeextractor.app.data.settings.ThemeMode
import com.codeextractor.app.domain.model.LatencyProfile
import com.codeextractor.app.domain.scene.SceneMode

// ─── Единственная поддерживаемая модель (3.1) ───
private val AVAILABLE_MODELS = listOf(
    "models/gemini-3.1-flash-live-preview" to "Gemini 3.1 Flash Live"
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

private val THEME_MODES = listOf(
    ThemeMode.AUTO  to "По системе",
    ThemeMode.LIGHT to "Светлая",
    ThemeMode.DARK  to "Тёмная"
)

private val SCENE_MODES = listOf(
    SceneMode.AVATAR       to "3D-аватар",
    SceneMode.VISUALIZER   to "Радужный визуализатор",
    SceneMode.CUSTOM_IMAGE to "Своя картинка (PNG)"
)

// ════════════════════════════════════════════════════════════
//  MAIN
// ════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onStartSession: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModel.flushPendingSave() }
    }
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val accent = MaterialTheme.colorScheme.primary
    val error  = MaterialTheme.colorScheme.error

    // SAF-лаунчер для выбора PNG фона
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importSceneBackground(uri)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                "Настройки",
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "Gemini 3.1 Flash Live — полная конфигурация",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // ── 1. ДОСТУП ──
            GeminiSection("1. Доступ (API)") {
                SecureApiKeyField(
                    value = s.apiKey,
                    label = "Основной API ключ",
                    placeholder = "AIza…",
                    onValueChange = { viewModel.update { copy(apiKey = it) } }
                )
                Hint("Личный ключ Google AI Studio. Без него приложение не работает. aistudio.google.com → Get API key.")

                SecureApiKeyField(
                    value = s.apiKeyBackup,
                    label = "Резервный API ключ",
                    placeholder = "Опционально",
                    onValueChange = {
                        viewModel.update { copy(apiKeyBackup = it, autoRotateKeys = it.isNotEmpty()) }
                    }
                )
                Hint("Используется автоматически при 429 (rate limit) на основном ключе.")

                GeminiSwitch(
                    title = "Авто-ротация ключей при 429",
                    checked = s.autoRotateKeys,
                    subtitle = "Переключаться на резервный ключ при исчерпании лимита.",
                    onCheckedChange = { viewModel.update { copy(autoRotateKeys = it) } }
                )
            }

            // ── 2. МОДЕЛЬ (только 3.1) ──
            GeminiSection("2. Модель ИИ") {
                GeminiDropdown(
                    label = "Модель",
                    selected = s.model,
                    options = AVAILABLE_MODELS.map { it.first },
                    displayNames = AVAILABLE_MODELS.map { it.second },
                    onSelected = { viewModel.update { copy(model = it) } }
                )
                Hint("Приложение работает только с Gemini 3.1 Flash Live — последней моделью с нативным аудио.")

                GeminiDropdown(
                    label = "Формат ответа (Modality)",
                    selected = s.responseModality,
                    options = RESPONSE_MODALITIES.map { it.first },
                    displayNames = RESPONSE_MODALITIES.map { it.second },
                    onSelected = { viewModel.update { copy(responseModality = it) } }
                )
                Hint("AUDIO — голос. TEXT — только текст (без звука).")
            }

            // ── 3. ГЕНЕРАЦИЯ ──
            GeminiSection("3. Параметры генерации") {
                GeminiSlider("Креативность (Temperature)", s.temperature, 0f..2f, "%.2f") {
                    viewModel.update { copy(temperature = it) }
                }
                Hint("0.0 — строгий. 1.0 — сбалансированный (default). 2.0 — максимально креативный.")

                GeminiSlider("Top-P", s.topP, 0f..1f, "%.2f") {
                    viewModel.update { copy(topP = it) }
                }
                Hint("0.95 — стандарт Gemini 3.1.")

                GeminiIntSlider("Top-K", s.topK, 0..100) {
                    viewModel.update { copy(topK = it) }
                }
                Hint("40 — дефолт. 0 — отключить.")

                GeminiIntSlider("Max tokens", s.maxOutputTokens, 256..65536, step = 256) {
                    viewModel.update { copy(maxOutputTokens = it) }
                }
                Hint("8192 — достаточно для разговора. 65536 — потолок 3.1.")

                GeminiSlider("Presence penalty", s.presencePenalty, -2f..2f, "%.2f") {
                    viewModel.update { copy(presencePenalty = it) }
                }
                GeminiSlider("Frequency penalty", s.frequencyPenalty, -2f..2f, "%.2f") {
                    viewModel.update { copy(frequencyPenalty = it) }
                }
            }

            // ── 4. ГОЛОС И ЯЗЫК ──
            GeminiSection("4. Голос и язык") {
                GeminiDropdown(
                    label = "Голос ассистента",
                    selected = s.voiceId,
                    options = AVAILABLE_VOICES.map { it.first },
                    displayNames = AVAILABLE_VOICES.map { it.second },
                    onSelected = { viewModel.update { copy(voiceId = it) } }
                )
                Hint("Смена голоса автоматически меняет пол 3D-аватара.")

                GeminiDropdown(
                    label = "Язык ответа",
                    selected = s.languageCode,
                    options = AVAILABLE_LANGUAGES.map { it.first },
                    displayNames = AVAILABLE_LANGUAGES.map { it.second },
                    onSelected = { viewModel.update { copy(languageCode = it) } }
                )

                GeminiDropdown(
                    label = "Профиль размышления",
                    selected = s.latencyProfile,
                    options = LatencyProfile.entries.map { it.name },
                    displayNames = LatencyProfile.entries.map { it.displayName },
                    onSelected = { viewModel.update { copy(latencyProfile = it) } }
                )
                Hint("UltraLow — мгновенно. Reasoning — модель думает дольше, но отвечает умнее.")
            }

            // ── 5. АУДИО ──
            GeminiSection("5. Аудио и микрофон") {
                GeminiIntSlider("Громкость воспроизведения (%)", s.playbackVolume, 0..100, step = 5) {
                    viewModel.update { copy(playbackVolume = it) }
                }
                Hint("Программное усиление. Если звук слишком тихий — увеличьте до 100 %.")

                GeminiIntSlider("Усиление микрофона (%)", s.micGain, 50..200, step = 10) {
                    viewModel.update { copy(micGain = it) }
                }
                Hint("Усиление захвата. 100 % — без изменений.")

                GeminiSwitch(
                    title = "Громкоговоритель (SPEAKER)",
                    checked = s.forceSpeakerOutput,
                    subtitle = "Воспроизведение через громкий динамик телефона, а не ушной earpiece.",
                    onCheckedChange = { viewModel.update { copy(forceSpeakerOutput = it) } }
                )

                GeminiSwitch(
                    title = "Эхоподавление (AEC)",
                    checked = s.useAec,
                    subtitle = "Устраняет эхо от динамика. Отключайте только при искажениях в наушниках.",
                    onCheckedChange = { viewModel.update { copy(useAec = it) } }
                )

                GeminiSwitch(
                    title = "Посылать audioStreamEnd при паузе",
                    checked = s.sendAudioStreamEnd,
                    subtitle = "Ускоряет ответ модели после «Стоп».",
                    onCheckedChange = { viewModel.update { copy(sendAudioStreamEnd = it) } }
                )

                GeminiIntSlider("Jitter-буфер (чанков)", s.jitterPreBufferChunks, 1..10) {
                    viewModel.update { copy(jitterPreBufferChunks = it) }
                }
                GeminiLongSlider("Таймаут jitter (мс)", s.jitterTimeoutMs, 50L..500L) {
                    viewModel.update { copy(jitterTimeoutMs = it) }
                }
                GeminiIntSlider("Очередь playback", s.playbackQueueCapacity, 64..512, step = 32) {
                    viewModel.update { copy(playbackQueueCapacity = it) }
                }
            }

            // ── 6. VAD ──
            GeminiSection("6. Определение речи (VAD)") {
                GeminiSwitch(
                    title = "Серверный VAD",
                    checked = s.enableServerVad,
                    subtitle = "Модель сама определяет конец вашей реплики.",
                    onCheckedChange = { viewModel.update { copy(enableServerVad = it) } }
                )
                GeminiSlider("Чувствительность начала речи", s.vadStartOfSpeechSensitivity, 0f..1f, "%.2f") {
                    viewModel.update { copy(vadStartOfSpeechSensitivity = it) }
                }
                GeminiSlider("Чувствительность конца речи", s.vadEndOfSpeechSensitivity, 0f..1f, "%.2f") {
                    viewModel.update { copy(vadEndOfSpeechSensitivity = it) }
                }
                GeminiIntSlider("Таймаут тишины (мс)", s.vadSilenceTimeoutMs, 0..5000, step = 100) {
                    viewModel.update { copy(vadSilenceTimeoutMs = it) }
                }
            }

            // ── 7. ТРАНСКРИПЦИЯ ──
            GeminiSection("7. Транскрипция") {
                GeminiSwitch(
                    title = "Транскрипция вашей речи",
                    checked = s.inputTranscription,
                    subtitle = "Показывать ваш текст в истории.",
                    onCheckedChange = { viewModel.update { copy(inputTranscription = it) } }
                )
                GeminiSwitch(
                    title = "Транскрипция речи модели",
                    checked = s.outputTranscription,
                    subtitle = "Показывать текст ответа ИИ.",
                    onCheckedChange = { viewModel.update { copy(outputTranscription = it) } }
                )
            }

            // ── 8. СЕССИЯ И ПАМЯТЬ ──
            GeminiSection("8. Сессия и память") {
                GeminiSwitch(
                    title = "Восстановление сессии",
                    checked = s.enableSessionResumption,
                    subtitle = "При обрыве сети диалог продолжится.",
                    onCheckedChange = { viewModel.update { copy(enableSessionResumption = it) } }
                )
                GeminiSwitch(
                    title = "Прозрачное восстановление",
                    checked = s.transparentResumption,
                    subtitle = "Не терять сообщения при reconnect.",
                    onCheckedChange = { viewModel.update { copy(transparentResumption = it) } }
                )
                GeminiSwitch(
                    title = "Сжатие контекста",
                    checked = s.enableContextCompression,
                    subtitle = "Сжимать старые сообщения — экономит токены.",
                    onCheckedChange = { viewModel.update { copy(enableContextCompression = it) } }
                )
                GeminiIntSlider("Порог сжатия (токенов)", s.compressionTriggerTokens, 0..128000, step = 1024) {
                    viewModel.update { copy(compressionTriggerTokens = it) }
                }
            }

            // ── 9. РЕКОННЕКТ ──
            GeminiSection("9. Переподключение") {
                GeminiIntSlider("Максимум попыток", s.maxReconnectAttempts, 1..20) {
                    viewModel.update { copy(maxReconnectAttempts = it) }
                }
                GeminiLongSlider("Базовая задержка (мс)", s.reconnectBaseDelayMs, 500L..10_000L, step = 500L) {
                    viewModel.update { copy(reconnectBaseDelayMs = it) }
                }
                GeminiLongSlider("Макс. задержка (мс)", s.reconnectMaxDelayMs, 5_000L..120_000L, step = 5000L) {
                    viewModel.update { copy(reconnectMaxDelayMs = it) }
                }
            }

            // ── 10. ИНСТРУМЕНТЫ ──
            GeminiSection("10. Инструменты и функции") {
                GeminiSwitch(
                    title = "Поиск в Google",
                    checked = s.enableGoogleSearch,
                    subtitle = "Модель может искать актуальную информацию.",
                    onCheckedChange = { viewModel.update { copy(enableGoogleSearch = it) } }
                )
                GeminiSwitch(
                    title = "Включить 10 тестовых функций",
                    checked = s.enableTestFunctions,
                    subtitle = "Передать декларации test_function_1..10 в сессию. Без этого тест не работает.",
                    onCheckedChange = { viewModel.update { copy(enableTestFunctions = it) } }
                )
            }

            // ── 11. ТЕМА ──
            GeminiSection("11. Тема оформления") {
                GeminiDropdown(
                    label = "Тема",
                    selected = s.themeMode.name,
                    options = THEME_MODES.map { it.first.name },
                    displayNames = THEME_MODES.map { it.second },
                    onSelected = { name ->
                        val mode = runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.AUTO)
                        viewModel.update { copy(themeMode = mode) }
                    }
                )
                Hint("«По системе» — следует системной теме Android.")
            }

            // ── 12. СЦЕНА АВАТАРА ──
            GeminiSection("12. Сцена аватара") {
                GeminiDropdown(
                    label = "Режим отображения",
                    selected = s.sceneMode,
                    options = SCENE_MODES.map { it.first.id },
                    displayNames = SCENE_MODES.map { it.second },
                    onSelected = { viewModel.update { copy(sceneMode = it) } }
                )
                Hint("3D-аватар (как сейчас), радужный визуализатор (чёрный фон + анимация) " +
                        "или своя PNG-картинка.")

                if (s.sceneMode == SceneMode.CUSTOM_IMAGE.id) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/png") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(if (s.sceneBgHasImage) "Заменить картинку" else "Выбрать PNG")
                        }
                        if (s.sceneBgHasImage) {
                            OutlinedButton(onClick = { viewModel.clearSceneBackground() }) {
                                Text("Удалить", color = error)
                            }
                        }
                    }
                    Hint("Поддерживается PNG из галереи. Максимум 2560×2560.")
                }
            }

            // ── 13. ЧАТ ──
            GeminiSection("13. Чат") {
                GeminiSlider("Размер текста", s.chatFontScale, 0.8f..1.4f, "%.2fx") {
                    viewModel.update { copy(chatFontScale = it) }
                }
                GeminiSwitch(
                    title = "Метки ролей («🎤 You» / «🔊 Gemini»)",
                    checked = s.chatShowRoleLabels,
                    subtitle = "Показывать подписи над каждым сообщением.",
                    onCheckedChange = { viewModel.update { copy(chatShowRoleLabels = it) } }
                )
                GeminiSwitch(
                    title = "Временные метки",
                    checked = s.chatShowTimestamps,
                    subtitle = "Показывать время каждого сообщения.",
                    onCheckedChange = { viewModel.update { copy(chatShowTimestamps = it) } }
                )
                GeminiSwitch(
                    title = "Автопрокрутка",
                    checked = s.chatAutoScroll,
                    subtitle = "Автоматически прокручивать к новым сообщениям.",
                    onCheckedChange = { viewModel.update { copy(chatAutoScroll = it) } }
                )
                GeminiIntSlider("Прозрачность фона (%)", s.chatBackgroundAlpha, 0..100, step = 5) {
                    viewModel.update { copy(chatBackgroundAlpha = it) }
                }
            }

            // ── 14. СИСТЕМНАЯ ИНСТРУКЦИЯ ──
            GeminiSection("14. Системная инструкция") {
                GeminiTextField(
                    value = s.systemInstruction,
                    onValueChange = { viewModel.update { copy(systemInstruction = it) } },
                    label = "Поведение и характер ИИ",
                    minLines = 4, maxLines = 10
                )
                Hint("Базовые правила для ИИ. Важно: фраза про «Выполни функцию N» сейчас уже есть — не удаляйте её, иначе тест функций сломается.")
            }

            // ── 15. DEBUG ──
            GeminiSection("15. Диагностика") {
                GeminiSwitch(
                    title = "Debug-лог на экране",
                    checked = s.showDebugLog,
                    subtitle = "Техлог поверх UI.",
                    onCheckedChange = { viewModel.update { copy(showDebugLog = it) } }
                )
                GeminiSwitch(
                    title = "Логировать WebSocket-фреймы",
                    checked = s.logRawWebSocketFrames,
                    subtitle = "Сырые сообщения сервера. Много данных.",
                    onCheckedChange = { viewModel.update { copy(logRawWebSocketFrames = it) } }
                )
                GeminiSwitch(
                    title = "Счётчик токенов",
                    checked = s.showUsageMetadata,
                    subtitle = "Показывать usage-metadata на главном экране.",
                    onCheckedChange = { viewModel.update { copy(showUsageMetadata = it) } }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onStartSession,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                enabled = s.apiKey.length >= 20
            ) {
                Text("Перейти к общению", fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimary)
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

// ════════════════════════════════════════════════════════════
//  REUSABLE COMPONENTS
// ════════════════════════════════════════════════════════════

@Composable
private fun GeminiSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 14.sp
    )
}

@Composable
private fun GeminiSwitch(
    title: String,
    checked: Boolean,
    subtitle: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                String.format(java.util.Locale.US, format, value),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                value.toString(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        val coerced = value.coerceIn(range.first, range.last).toFloat()
        Slider(
            value = coerced,
            onValueChange = { new ->
                val rounded = ((new / step).toInt() * step).coerceIn(range.first, range.last)
                onValueChange(rounded)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                value.toString(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        val coerced = value.coerceIn(range.first, range.last).toFloat()
        Slider(
            value = coerced,
            onValueChange = { new ->
                val rounded = ((new.toLong() / step) * step).coerceIn(range.first, range.last)
                onValueChange(rounded)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
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
    maxLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        placeholder = {
            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        },
        modifier = Modifier.fillMaxWidth(),
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary
        )
    )
}

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
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

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
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )
        androidx.compose.material3.ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
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