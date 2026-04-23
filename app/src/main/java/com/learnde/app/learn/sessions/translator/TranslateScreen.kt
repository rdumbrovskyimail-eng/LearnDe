// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorScreen.kt
//
// Живой переводчик укр/рус ↔ немецкий.
// Одна большая кнопка Start/Stop.
// Транскрипт двух сторон с флагами 🇺🇦🇷🇺 и 🇩🇪.
// Визуальный индикатор "кто сейчас говорит".
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.translator

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    onBack: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
) {
    val learnState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val isActive = learnState.sessionId == "translator" &&
            learnState.connectionStatus != LearnConnectionStatus.Disconnected

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            learnCoreViewModel.onIntent(LearnCoreIntent.Start("translator"))
        } else {
            Toast.makeText(context, "Для переводчика нужен микрофон", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Переводчик",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // При выходе автоматически останавливаем сессию
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
                Spacer(Modifier.height(8.dp))

                // ═══ Верхняя подсказка ═══
                LanguagePairBanner(isActive = isActive)

                Spacer(Modifier.height(12.dp))

                // ═══ Статус "кто говорит" ═══
                SpeakerStatusIndicator(
                    isActive = isActive,
                    isAiSpeaking = learnState.isAiSpeaking,
                    isMicActive = learnState.isMicActive,
                )

                Spacer(Modifier.height(12.dp))

                // ═══ Транскрипт (весь оставшийся экран) ═══
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(8.dp)
                ) {
                    if (learnState.transcript.isEmpty()) {
                        EmptyTranscriptHint(isActive = isActive)
                    } else {
                        TranscriptList(messages = learnState.transcript)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ═══ Главная кнопка Start/Stop ═══
                MainActionButton(
                    isActive = isActive,
                    connectionStatus = learnState.connectionStatus,
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

                Spacer(Modifier.height(16.dp))
            }

            // ФИНАЛ: Анимация загрузки
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
//  ВИДЖЕТЫ
// ═══════════════════════════════════════════════════════════

@Composable
private fun LanguagePairBanner(isActive: Boolean) {
    val alpha = if (isActive) 1f else 0.55f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f * alpha))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🇺🇦 🇷🇺", fontSize = 24.sp)
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Filled.SwapHoriz,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text("🇩🇪", fontSize = 24.sp)
    }
}

@Composable
private fun SpeakerStatusIndicator(
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
) {
    val (label, color) = when {
        !isActive -> "Нажми «Старт», чтобы начать" to MaterialTheme.colorScheme.onSurfaceVariant
        isAiSpeaking -> "🔊 Перевод озвучивается…" to Color(0xFF1E88E5)
        isMicActive -> "🎤 Слушаю… Говорите" to Color(0xFF43A047)
        else -> "Подключение…" to Color(0xFFFB8C00)
    }

    // Пульсация для активных состояний
    val pulse = rememberInfiniteTransition(label = "speakerPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val isPulsing = isActive && (isAiSpeaking || isMicActive)
    val finalAlpha = if (isPulsing) pulseAlpha else 1f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f * finalAlpha))
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = color.copy(alpha = finalAlpha),
        )
    }
}

@Composable
private fun EmptyTranscriptHint(isActive: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Translate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (isActive) "Говорите — я переведу" else "Нажмите «Старт»",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium,
            )
            if (isActive) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Один говорит по-русски/украински,\nдругой — по-немецки",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun TranscriptList(messages: List<ConversationMessage>) {
    val listState = rememberLazyListState()

    // Автоскролл к последнему сообщению
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(messages, key = { it.timestamp }) { msg ->
            TranscriptBubble(message = msg)
        }
    }
}

@Composable
private fun TranscriptBubble(message: ConversationMessage) {
    val isUser = message.role == ConversationMessage.ROLE_USER
    val text = message.text
    val isGerman = detectIsGerman(text)

    // Флаг и цвет в зависимости от языка реплики
    val (flag, tint) = when {
        isGerman -> "🇩🇪" to Color(0xFFFFC107)
        else -> "🇺🇦🇷🇺" to Color(0xFF4FC3F7)
    }

    val bgColor = tint.copy(alpha = 0.14f)
    val borderColor = tint.copy(alpha = 0.30f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(flag, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isUser) "Вы сказали" else "Перевод",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = tint,
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
    }
}

@Composable
private fun MainActionButton(
    isActive: Boolean,
    connectionStatus: LearnConnectionStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val buttonColor by animateColorAsState(
        targetValue = if (isActive) Color(0xFFE53935) else Color(0xFF43A047),
        animationSpec = tween(400),
        label = "btnColor"
    )

    // Пульсация когда активен
    val pulse = rememberInfiniteTransition(label = "btnPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val label = when {
        isActive -> "СТОП"
        connectionStatus == LearnConnectionStatus.Connecting ||
            connectionStatus == LearnConnectionStatus.Negotiating -> "Подключение…"
        else -> "СТАРТ"
    }

    val icon = if (isActive) Icons.Filled.MicOff else Icons.Filled.Mic

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .scale(if (isActive) pulseScale else 1f)
            .clip(RoundedCornerShape(36.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(buttonColor, buttonColor.copy(alpha = 0.85f))
                )
            )
            .clickable(enabled = connectionStatus != LearnConnectionStatus.Connecting) {
                if (isActive) onStop() else onStart()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.sp,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  ХЕЛПЕРЫ
// ═══════════════════════════════════════════════════════════

/**
 * Грубая эвристика определения языка реплики для визуализации флага.
 * Если в строке есть umlaut (ä/ö/ü/ß) или часто встречающиеся немецкие слова —
 * считаем немецкой. Иначе русской/украинской.
 */
private fun detectIsGerman(text: String): Boolean {
    if (text.isBlank()) return false
    // Umlauts и ß — 100% немецкий
    if (text.any { it in "äöüßÄÖÜ" }) return true
    // Кириллица — точно не немецкий
    if (text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' || it == 'і' || it == 'ї' || it == 'є' }) return false
    // Латиница без umlaut — вероятно немецкий (англ редок в этом контексте)
    if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) return true
    return false
}
