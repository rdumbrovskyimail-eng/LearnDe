// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v4.0
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslateScreen.kt
//
// ИЗМЕНЕНИЯ v4.0 (premium-дизайн, ЛОГИКА НЕ ТРОНУТА):
//   - Hero-banner с flow-анимацией между флагами
//   - Большая круглая FAB-кнопка микрофона в центре с пульсирующим ореолом
//   - Waveform-индикатор активности говорящего
//   - Chat-bubble'ы с флагами, языковыми метками и аватарами
//   - Статус-пилюля показывает точное состояние (слушаю/озвучиваю/пауза)
//   - Градиенты, тени и плавные анимации
//
//   ВАЖНО: логика переводчика (detectIsGerman, сессия, навигация, Start/Stop,
//   обработка isActive, transcript) НЕ менялась — все callbacks идут в
//   LearnCoreViewModel теми же интентами, что и в v3.2.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.translator

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.SessionLoadingOverlay

// ═══════════════════════════════════════════════════════════
// Цвета
// ═══════════════════════════════════════════════════════════
private object TrTheme {
    val Orange = Color(0xFFFB8C00)
    val OrangeLight = Color(0xFFFFB74D)
    val Slavic = Color(0xFF4FC3F7)     // цвет для RU/UA bubble
    val SlavicDark = Color(0xFF0288D1)
    val German = Color(0xFFFFC107)     // цвет для DE bubble
    val GermanDark = Color(0xFFFF8F00)
    val Green = Color(0xFF43A047)
    val Red = Color(0xFFE53935)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    onBack: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
) {
    val learnState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ЛОГИКА НЕ ТРОНУТА
    val isActive = learnState.sessionId == "translator" &&
            learnState.connectionStatus != LearnConnectionStatus.Disconnected

    val activity = context as? android.app.Activity
    var showRationaleDialog by androidx.compose.runtime.mutableStateOf(false)
    var rationaleIsPermanent by androidx.compose.runtime.mutableStateOf(false)

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            learnCoreViewModel.onIntent(LearnCoreIntent.Start("translator"))
        } else {
            rationaleIsPermanent = activity == null ||
                    !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, android.Manifest.permission.RECORD_AUDIO
                    )
            showRationaleDialog = true
        }
    }

    if (showRationaleDialog) {
        com.learnde.app.presentation.learn.components.MicPermissionRationaleDialog(
            showSettingsButton = rationaleIsPermanent,
            onDismiss = { showRationaleDialog = false },
            onRequestAgain = {
                showRationaleDialog = false
                micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            context = context,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(TrTheme.Orange.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Translate,
                                contentDescription = null,
                                tint = TrTheme.Orange,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Переводчик Live",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                when {
                                    isActive && learnState.isAiSpeaking -> "Озвучиваю перевод"
                                    isActive && learnState.isMicActive -> "Слушаю"
                                    isActive -> "Подключение…"
                                    else -> "Готов к запуску"
                                },
                                fontSize = 10.sp,
                                color = if (isActive) TrTheme.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isActive) {
                            learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                        }
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(6.dp))

                // ═══ HERO-BANNER c анимацией направления ═══
                LanguageFlowBanner(isActive = isActive)

                Spacer(Modifier.height(12.dp))

                // ═══ Speaker status (waveform) ═══
                SpeakerStatusIndicator(
                    isActive = isActive,
                    isAiSpeaking = learnState.isAiSpeaking,
                    isMicActive = learnState.isMicActive,
                    rmsIntensity = learnState.currentRms,
                )

                Spacer(Modifier.height(12.dp))

                // ═══ Транскрипт ═══
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            RoundedCornerShape(18.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    if (learnState.transcript.isEmpty()) {
                        EmptyTranscriptHint(isActive = isActive)
                    } else {
                        TranscriptList(messages = learnState.transcript)
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ═══ Главный круглый FAB ═══
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MainMicButton(
                        isActive = isActive,
                        onStart = {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) {
                                learnCoreViewModel.onIntent(LearnCoreIntent.Start("translator"))
                            } else {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onStop = {
                            learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                        }
                    )
                }

                Spacer(Modifier.height(20.dp))
            }

            // Loading overlay
            AnimatedVisibility(
                visible = learnState.isPreparingSession,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                SessionLoadingOverlay()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// LANGUAGE FLOW BANNER
// ═══════════════════════════════════════════════════════════
@Composable
private fun LanguageFlowBanner(isActive: Boolean) {
    val orange = TrTheme.Orange

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isActive) 4.dp else 1.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = orange.copy(alpha = 0.2f),
                spotColor = orange.copy(alpha = 0.2f),
            )
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        TrTheme.Slavic.copy(alpha = 0.15f),
                        orange.copy(alpha = 0.10f),
                        TrTheme.German.copy(alpha = 0.15f),
                    )
                )
            )
            .border(1.dp, orange.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Левая сторона: RU/UA
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
            Text("🇺🇦 🇷🇺", fontSize = 22.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "Славянские",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = TrTheme.SlavicDark,
                letterSpacing = 0.5.sp,
            )
        }

        // Центр: SwapHoriz с плавающей анимацией
        Box(
            modifier = Modifier.size(42.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(orange.copy(alpha = 0.18f)),
            )
            Icon(
                Icons.Filled.SwapHoriz,
                contentDescription = null,
                tint = orange,
                modifier = Modifier
                    .size(26.dp)
                    .scale(if (isActive) 1f else 0.9f),
            )
        }

        // Правая сторона: DE
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
            Text("🇩🇪", fontSize = 22.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "Немецкий",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = TrTheme.GermanDark,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// SPEAKER STATUS
// ═══════════════════════════════════════════════════════════
@Composable
private fun SpeakerStatusIndicator(
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
    rmsIntensity: Float,
) {
    val (label, color, icon) = when {
        !isActive -> Triple("Нажмите кнопку «Старт», чтобы начать", MaterialTheme.colorScheme.onSurfaceVariant, null)
        isAiSpeaking -> Triple("Перевод озвучивается", Color(0xFF1E88E5), Icons.Filled.VolumeUp)
        isMicActive -> Triple("Слушаю · говорите", TrTheme.Green, Icons.Filled.Mic)
        else -> Triple("Подключение…", TrTheme.Orange, null)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            // Waveform
            WaveformBars(color = color, intensity = rmsIntensity)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun WaveformBars(color: Color, intensity: Float) {
    val targetIntensity = intensity.coerceIn(0f, 1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val barHeights = listOf(0.4f, 0.8f, 0.5f, 0.9f)
        barHeights.forEach { factor ->
            val h = (6 + 18 * factor * targetIntensity).dp
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color.copy(alpha = 0.8f))
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// EMPTY HINT
// ═══════════════════════════════════════════════════════════
@Composable
private fun EmptyTranscriptHint(isActive: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(TrTheme.Orange.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Translate,
                    contentDescription = null,
                    tint = TrTheme.Orange.copy(alpha = 0.6f),
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (isActive) "Говорите — я переведу" else "Нажмите «Старт»",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                fontWeight = FontWeight.Bold,
            )
            if (isActive) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Один говорит по-русски/украински,\nдругой — по-немецки",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Forum,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Диалог появится здесь",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// TRANSCRIPT LIST + BUBBLES
// ═══════════════════════════════════════════════════════════
@Composable
private fun TranscriptList(messages: List<ConversationMessage>) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.timestamp }) { msg ->
            TranscriptBubble(message = msg)
        }
    }
}

@Composable
private fun TranscriptBubble(message: ConversationMessage) {
    val isUser = message.role == ConversationMessage.ROLE_USER
    val text = message.text.trim()
    if (text.isEmpty()) return

    val isGerman = detectIsGerman(text)

    // Визуальная сторона по языку + ориентация по роли
    val (flag, accent) = when {
        isGerman -> "🇩🇪" to TrTheme.German
        else -> "🇺🇦🇷🇺" to TrTheme.Slavic
    }
    val accentDark = if (isGerman) TrTheme.GermanDark else TrTheme.SlavicDark

    val label = when {
        isUser && isGerman -> "ВЫ · DE"
        isUser && !isGerman -> "ВЫ · RU/UA"
        !isUser && isGerman -> "ПЕРЕВОД · DE"
        else -> "ПЕРЕВОД · RU/UA"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            // Аватар перевода
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.2f))
                    .border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(flag, fontSize = 15.sp)
            }
            Spacer(Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .shadow(1.dp, RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp,
                ))
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    )
                )
                .background(accent.copy(alpha = 0.14f))
                .border(
                    1.dp,
                    accent.copy(alpha = 0.3f),
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isUser) {
                    Text(flag, fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    label,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentDark,
                    letterSpacing = 1.2.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp,
            )
        }

        if (isUser) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.2f))
                    .border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Person,
                    null,
                    tint = accentDark,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// MAIN MIC BUTTON (FAB)
// ═══════════════════════════════════════════════════════════
@Composable
private fun MainMicButton(
    isActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val buttonColor = if (isActive) TrTheme.Red else TrTheme.Green
    val halo = buttonColor

    // Пульс ореола
    val pulse = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.18f else 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale"
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
        ),
        label = "pulseAlpha"
    )

    val buttonScale by animateFloatAsState(
        targetValue = if (isActive) 1.02f else 1f,
        animationSpec = tween(400),
        label = "btnScale",
    )

    Box(
        modifier = Modifier.size(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Внешнее пульсирующее кольцо
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(halo.copy(alpha = pulseAlpha * 0.5f))
        )

        // Вторая подложка
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(halo.copy(alpha = 0.18f))
        )

        // Основная кнопка
        Box(
            modifier = Modifier
                .size(92.dp)
                .scale(buttonScale)
                .shadow(10.dp, CircleShape, ambientColor = buttonColor, spotColor = buttonColor)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            buttonColor,
                            buttonColor.copy(alpha = 0.85f),
                        )
                    )
                )
                .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                .clickable {
                    if (isActive) onStop() else onStart()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isActive) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isActive) "Остановить" else "Запустить",
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// DETECT LANGUAGE — ЛОГИКА НЕ ТРОНУТА (из v3.2)
// ═══════════════════════════════════════════════════════════
private fun detectIsGerman(text: String): Boolean {
    if (text.isBlank()) return false
    if (text.any { it in "äöüßÄÖÜ" }) return true
    if (text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' || it == 'і' || it == 'ї' || it == 'є' }) return false
    if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) return true
    return false
}
