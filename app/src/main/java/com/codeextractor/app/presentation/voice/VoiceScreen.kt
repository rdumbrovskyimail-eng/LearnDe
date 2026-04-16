// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/presentation/voice/VoiceScreen.kt
// Изменения:
//   + Новый Gemini-минималистичный дизайн через MaterialTheme
//   + 3 режима сцены (AVATAR / VISUALIZER / CUSTOM_IMAGE)
//   + Кнопка «Функции» → переход на FunctionsTestScreen
//   + Кнопка fullscreen для сцены
//   + Чат с настройками из state (font scale, role labels, timestamps, bg alpha)
//   + Удалены все неиспользуемые intent'ы
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.presentation.voice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeextractor.app.data.BackgroundImageStore
import com.codeextractor.app.domain.model.ConversationMessage
import com.codeextractor.app.domain.scene.SceneMode
import com.codeextractor.app.presentation.avatar.AudioVisualizerScene
import com.codeextractor.app.presentation.avatar.AvatarScene
import com.codeextractor.app.presentation.navigation.VoiceGender
import com.codeextractor.app.util.resolve
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VoiceScreen(
    viewModel: VoiceViewModel = hiltViewModel(),
    onOpenEditor: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenFunctions: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val avatarIndex = VoiceGender.avatarIndexForVoice(state.currentVoiceId)

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
                        else Modifier.weight(0.55f, fill = true)
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

                // Верхний правый: ⛶ fullscreen
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

                // Нижний правый: ⚙ + 🧪 + Edit
                if (!state.isSceneFullscreen) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SceneIconButton(Icons.Filled.Tune, "Функции/Тест", onOpenFunctions)
                        SceneIconButton(Icons.Filled.Settings, "Настройки", onOpenSettings)
                    }
                }

                if (state.showUsageMetadata && state.totalTokens > 0 && !state.isSceneFullscreen) {
                    Text(
                        text = "Tokens: ${state.totalTokens}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (!state.isSceneFullscreen) {
                // ═══ CHAT + CONTROLS ═══
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    if (state.showApiKeyInput) {
                        ApiKeyInput { viewModel.onIntent(VoiceIntent.SubmitApiKey(it)) }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    val chatBgAlpha = (state.chatBackgroundAlpha / 100f).coerceIn(0f, 1f)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = chatBgAlpha))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
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

                    Spacer(modifier = Modifier.height(10.dp))

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

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
//  SCENE SWITCHER
// ════════════════════════════════════════════════════════════
/**
 * Единый контейнер сцены — принимает BoxScope из родительского Box
 * и корректно переключается между compact (правый верхний угол)
 * и fullscreen (на весь экран) режимами.
 */
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
            .fillMaxHeight(0.58f)
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
        Text(
            text = state.connectionStatus.label,
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium
        )
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
    // surfaceContainerHigh доступен с Material3 1.2+. На случай старого кеша —
    // мы экспортировали его и в Theme.kt, и он гарантированно есть в BOM 2026.03.01.
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val label = if (isUser) "🎤 Вы" else "🔊 Gemini"

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
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1E8E3E),
                disabledContainerColor = Color(0xFF1E8E3E).copy(alpha = 0.25f)
            )
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Говорить", color = Color.White, fontSize = 14.sp)
        }
        Button(
            onClick = onStop, enabled = isRecording,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD93025),
                disabledContainerColor = Color(0xFFD93025).copy(alpha = 0.25f)
            )
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Стоп", color = Color.White, fontSize = 14.sp)
        }
    }
}