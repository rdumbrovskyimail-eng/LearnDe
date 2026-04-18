// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/presentation/voice/VoiceScreen.kt
//
// Изменения:
//   • DiagnosticPanel — 5 кнопок для поиска источника 1007:
//     Baseline / -Thinking / -VAD / -Session / -Transcription + FULL
//   • DiagnosticLogPanel — история последних 10 тестов с результатами
//   • StatusBadge показывает какой профиль сейчас тестируется
//   • DisposableEffect с resume/pause аватар-аниматора (сохранено)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.scene.SceneMode
import com.learnde.app.presentation.avatar.AudioVisualizerScene
import com.learnde.app.presentation.avatar.AvatarScene
import com.learnde.app.presentation.navigation.VoiceGender
import com.learnde.app.util.resolve
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VoiceScreen(
    viewModel: VoiceViewModel = hiltViewModel(),
    onOpenEditor: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenFunctions: () -> Unit = {},
    onOpenLearnHub: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val avatarIndex = VoiceGender.avatarIndexForVoice(state.currentVoiceId)

    DisposableEffect(Unit) {
        viewModel.avatarAnimator.resume()
        onDispose { viewModel.avatarAnimator.pause() }
    }

    var showMicRationale by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onIntent(VoiceIntent.ToggleMic)
        else Toast.makeText(context, "Микрофон необходим для голосового общения", Toast.LENGTH_SHORT).show()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val has = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!has) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is VoiceEffect.ShowToast ->
                    Toast.makeText(context, effect.message.resolve(context), Toast.LENGTH_SHORT).show()
                is VoiceEffect.SaveLogToFile ->
                    Toast.makeText(context, "Log: ${effect.content.length} chars", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.transcript.size, state.chatAutoScroll) {
        if (state.chatAutoScroll && state.transcript.isNotEmpty()) {
            listState.animateScrollToItem(state.transcript.size - 1)
        }
    }

    if (showMicRationale) {
        AlertDialog(
            onDismissRequest = { showMicRationale = false },
            title = { Text("Доступ к микрофону") },
            text = { Text("Для работы голосового ассистента необходим доступ к микрофону.") },
            confirmButton = {
                TextButton(onClick = {
                    showMicRationale = false
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = { showMicRationale = false }) { Text("Отмена") }
            }
        )
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            // ═══ SCENE ═══
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (state.isSceneFullscreen) Modifier.weight(1f, fill = true)
                        else Modifier.weight(0.45f, fill = true)
                    )
            ) {
                SceneContainer(
                    state = state,
                    viewModel = viewModel,
                    avatarIndex = avatarIndex
                )

                StatusBadge(
                    state = state,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                )

                IconButton(
                    onClick = { viewModel.onIntent(VoiceIntent.ToggleFullscreenScene) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.4f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        if (state.isSceneFullscreen) Icons.Filled.FullscreenExit
                        else Icons.Filled.Fullscreen,
                        contentDescription = "Развернуть"
                    )
                }

                if (!state.isSceneFullscreen) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SceneIconButton(Icons.Filled.School, "Изучение", onOpenLearnHub)
                        SceneIconButton(Icons.Filled.Tune, "Функции", onOpenFunctions)
                        SceneIconButton(Icons.Filled.Settings, "Настройки", onOpenSettings)
                    }
                }
            }

            if (!state.isSceneFullscreen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(6.dp))

                    if (state.showApiKeyInput) {
                        ApiKeyInput { viewModel.onIntent(VoiceIntent.SubmitApiKey(it)) }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // ═══ ДИАГНОСТИЧЕСКАЯ ПАНЕЛЬ ═══
                    DiagnosticPanel(
                        state = state,
                        onIntent = viewModel::onIntent
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // ═══ ЛОГ РЕЗУЛЬТАТОВ ТЕСТОВ ═══
                    if (state.diagnosticLog.isNotEmpty()) {
                        DiagnosticLogPanel(log = state.diagnosticLog)
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // ═══ ЧАТ ═══
                    val chatBgAlpha = (state.chatBackgroundAlpha / 100f).coerceIn(0f, 1f)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = chatBgAlpha))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(state.transcript, key = { "${it.timestamp}_${it.role}" }) { msg ->
                            ChatBubble(
                                message = msg,
                                fontScale = state.chatFontScale,
                                showLabel = state.chatShowRoleLabels,
                                showTimestamp = state.chatShowTimestamps
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    ControlButtons(
                        state = state,
                        onToggleMic = {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) viewModel.onIntent(VoiceIntent.ToggleMic)
                            else showMicRationale = true
                        },
                        onStop = { viewModel.onIntent(VoiceIntent.ToggleMic) },
                        onSaveLog = { viewModel.onIntent(VoiceIntent.SaveLog) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
//  DIAGNOSTIC PANEL — 5 кнопок для поиска 1007
// ════════════════════════════════════════════════════════════

@Composable
private fun DiagnosticPanel(
    state: VoiceState,
    onIntent: (VoiceIntent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "🔬 Диагностика setup 1007",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Текущий: ${state.lastTestedProfile.shortLabel}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        // Ряд кнопок с горизонтальным скроллом
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DiagnosticButton(
                profile = DiagnosticProfile.BASELINE,
                isActive = state.lastTestedProfile == DiagnosticProfile.BASELINE,
                color = Color(0xFF1976D2),  // синий
                onClick = { onIntent(VoiceIntent.ConnectBaseline) }
            )
            DiagnosticButton(
                profile = DiagnosticProfile.WITHOUT_THINKING,
                isActive = state.lastTestedProfile == DiagnosticProfile.WITHOUT_THINKING,
                color = Color(0xFF7B1FA2),  // фиолетовый
                onClick = { onIntent(VoiceIntent.ConnectWithoutThinking) }
            )
            DiagnosticButton(
                profile = DiagnosticProfile.WITHOUT_VAD,
                isActive = state.lastTestedProfile == DiagnosticProfile.WITHOUT_VAD,
                color = Color(0xFFE64A19),  // оранжевый
                onClick = { onIntent(VoiceIntent.ConnectWithoutVad) }
            )
            DiagnosticButton(
                profile = DiagnosticProfile.WITHOUT_SESSION_MGMT,
                isActive = state.lastTestedProfile == DiagnosticProfile.WITHOUT_SESSION_MGMT,
                color = Color(0xFF00796B),  // бирюзовый
                onClick = { onIntent(VoiceIntent.ConnectWithoutSessionMgmt) }
            )
            DiagnosticButton(
                profile = DiagnosticProfile.WITHOUT_TRANSCRIPTION,
                isActive = state.lastTestedProfile == DiagnosticProfile.WITHOUT_TRANSCRIPTION,
                color = Color(0xFFC2185B),  // малиновый
                onClick = { onIntent(VoiceIntent.ConnectWithoutTranscription) }
            )
            DiagnosticButton(
                profile = DiagnosticProfile.FULL,
                isActive = state.lastTestedProfile == DiagnosticProfile.FULL,
                color = Color(0xFF1E8E3E),  // зелёный
                onClick = { onIntent(VoiceIntent.ConnectFull) }
            )
        }

        Text(
            text = state.lastTestedProfile.description,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun DiagnosticButton(
    profile: DiagnosticProfile,
    isActive: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) color else color.copy(alpha = 0.55f),
            contentColor = Color.White
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = profile.shortLabel,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun DiagnosticLogPanel(log: List<DiagnosticResult>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(8.dp)
            .heightIn(max = 120.dp)
    ) {
        Text(
            "📋 История тестов",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(log.reversed()) { entry ->
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date(entry.timestamp))
                Text(
                    text = "$timeStr · ${entry.profile.shortLabel} · ${entry.result}",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
//  SCENE SWITCHER
// ════════════════════════════════════════════════════════════

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SceneContainer(
    state: VoiceState,
    viewModel: VoiceViewModel,
    avatarIndex: Int
) {
    val sceneShape = if (state.isSceneFullscreen) RoundedCornerShape(0.dp)
                     else RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)

    val base: Modifier = if (state.isSceneFullscreen) {
        Modifier.matchParentSize()
    } else {
        Modifier
            .fillMaxWidth(0.55f)
            .fillMaxHeight(0.68f)
            .align(Alignment.TopEnd)
    }

    Box(
        modifier = base
            .clip(sceneShape)
            .background(Color.Black)
    ) {
        val effectiveMode = when {
            state.sceneMode == SceneMode.CUSTOM_IMAGE && !state.sceneBgHasImage -> SceneMode.AVATAR
            else -> state.sceneMode
        }
        when (effectiveMode) {
            SceneMode.AVATAR -> AvatarScene(
                modifier = Modifier.fillMaxSize(),
                renderBuffer = viewModel.avatarAnimator.renderBuffer,
                avatarIndex = avatarIndex
            )
            SceneMode.VISUALIZER -> AudioVisualizerScene(
                modifier = Modifier.fillMaxSize(),
                playbackSync = viewModel.audioPlaybackFlow
            )
            SceneMode.CUSTOM_IMAGE -> CustomImageScene(viewModel = viewModel)
        }
    }
}

@Composable
private fun CustomImageScene(viewModel: VoiceViewModel) {
    val bmp by viewModel.backgroundBitmap.collectAsState()
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } ?: Text(
            "Загрузите PNG в настройках → «Сцена аватара»",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

// ════════════════════════════════════════════════════════════
//  COMPONENTS
// ════════════════════════════════════════════════════════════

@Composable
private fun SceneIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.4f),
            contentColor = Color.White
        )
    ) {
        Icon(icon, contentDescription = desc)
    }
}

@Composable
private fun StatusBadge(state: VoiceState, modifier: Modifier = Modifier) {
    val color = when (state.connectionStatus) {
        ConnectionStatus.Disconnected -> MaterialTheme.colorScheme.error
        ConnectionStatus.Connecting, ConnectionStatus.Negotiating, ConnectionStatus.Reconnecting -> Color(0xFFF29900)
        ConnectionStatus.Ready -> Color(0xFF1E8E3E)
        ConnectionStatus.Recording -> Color(0xFFD93025)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (state.connectionStatus == ConnectionStatus.Recording) PulsingDot(color = color)
        else Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = state.connectionStatus.label,
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
            if (state.lastTestedProfile != DiagnosticProfile.FULL) {
                Text(
                    text = state.lastTestedProfile.shortLabel,
                    color = Color.Yellow.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (state.isAiSpeaking) {
            Spacer(Modifier.width(6.dp))
            Text("🔊", fontSize = 11.sp)
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val scale by t.animateFloat(0.6f, 1.3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "s")
    val alpha by t.animateFloat(0.8f, 0.2f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "a")
    Box(contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(14.dp).scale(scale).alpha(alpha).clip(CircleShape).background(color))
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun ApiKeyInput(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("API Key") }, singleLine = true, modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { onSubmit(text.trim()) }) { Text("OK") }
    }
}

@Composable
private fun ChatBubble(
    message: ConversationMessage,
    fontScale: Float,
    showLabel: Boolean,
    showTimestamp: Boolean
) {
    val isUser = message.role == ConversationMessage.ROLE_USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val label = if (isUser) "🎤 Вы" else "🔊 Gemini"
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        if (showLabel) {
            val ts = if (showTimestamp)
                " · ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(message.timestamp))}"
            else ""
            Text(
                text = label + ts,
                fontSize = (10 * fontScale).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!isUser) {
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(message.text)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Копировать",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.width(2.dp))
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = message.text,
                    fontSize = (13 * fontScale).sp,
                    color = textColor
                )
            }
            if (isUser) {
                Spacer(Modifier.width(2.dp))
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(message.text)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Копировать",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButtons(
    state: VoiceState,
    onToggleMic: () -> Unit,
    onStop: () -> Unit,
    onSaveLog: () -> Unit
) {
    val isReady = state.connectionStatus == ConnectionStatus.Ready
    val isRecording = state.connectionStatus == ConnectionStatus.Recording

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        Button(
            onClick = onToggleMic, enabled = isReady,
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1E8E3E),
                disabledContainerColor = Color(0xFF1E8E3E).copy(alpha = 0.25f)
            )
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Говорить", color = Color.White, fontSize = 13.sp)
        }
        Button(
            onClick = onStop, enabled = isRecording,
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD93025),
                disabledContainerColor = Color(0xFFD93025).copy(alpha = 0.25f)
            )
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Стоп", color = Color.White, fontSize = 13.sp)
        }
    }
}
