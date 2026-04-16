// ═══════════════════════════════════════════════════════════
// ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/presentation/voice/VoiceScreen.kt
// Изменения:
//   + POST_NOTIFICATIONS runtime permission (Android 13+)
//   + RECORD_AUDIO rationale dialog
//   + Permission checks before mic start
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.presentation.voice

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeextractor.app.domain.model.ConversationMessage
import com.codeextractor.app.presentation.avatar.AvatarScene
import com.codeextractor.app.presentation.navigation.VoiceGender
import com.codeextractor.app.util.resolve

@Composable
fun VoiceScreen(
    viewModel: VoiceViewModel = hiltViewModel(),
    onOpenEditor: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val avatarIndex = VoiceGender.avatarIndexForVoice(state.currentVoiceId)

    // ── Mic permission rationale dialog ──
    var showMicRationale by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onIntent(VoiceIntent.ToggleMic)
        else Toast.makeText(context, "Микрофон необходим для голосового общения", Toast.LENGTH_SHORT).show()
    }

    // ── Notification permission (Android 13+) ──
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — FG service will still work, just no notification on 13+ */ }

    // Запрашиваем notification permission при первом запуске на Android 13+
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotifPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) listState.animateScrollToItem(state.transcript.size - 1)
    }

    // ── Mic rationale dialog ──
    if (showMicRationale) {
        AlertDialog(
            onDismissRequest = { showMicRationale = false },
            title = { Text("Доступ к микрофону") },
            text = { Text("Для работы голосового ассистента необходим доступ к микрофону. Нажмите «Разрешить» для продолжения.") },
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

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            // ══════════════════════════════════════════════════════
            //  TOP — 3D Avatar
            // ══════════════════════════════════════════════════════
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier = Modifier.fillMaxWidth(0.5f).fillMaxHeight(0.5f)
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(bottomStart = 16.dp))
                ) {
                    AvatarScene(
                        modifier = Modifier.fillMaxSize(),
                        renderBuffer = viewModel.avatarAnimator.renderBuffer,
                        avatarIndex = avatarIndex,
                    )
                }

                StatusBadge(state = state, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))

                Row(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
                    ) { Text("⚙", color = Color.White, fontSize = 14.sp) }
                    OutlinedButton(
                        onClick = onOpenEditor,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
                    ) { Text("Edit", color = Color.White, fontSize = 11.sp) }
                }

                if (state.showUsageMetadata && state.totalTokens > 0) {
                    Text(
                        text = "Tokens: ${state.totalTokens}",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // ══════════════════════════════════════════════════════
            //  BOTTOM — Chat + Controls
            // ══════════════════════════════════════════════════════
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(6.dp))

                if (state.showApiKeyInput) {
                    ApiKeyInput(onSubmit = { viewModel.onIntent(VoiceIntent.SubmitApiKey(it)) })
                    Spacer(modifier = Modifier.height(6.dp))
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.transcript, key = { "${it.timestamp}_${it.role}" }) { msg ->
                        ChatBubble(message = msg)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                ControlButtons(
                    state = state,
                    onToggleMic = {
                        // ═══ FIX: permission rationale перед запросом ═══
                        val hasMicPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasMicPermission) {
                            viewModel.onIntent(VoiceIntent.ToggleMic)
                        } else {
                            showMicRationale = true
                        }
                    },
                    onStop = { viewModel.onIntent(VoiceIntent.ToggleMic) },
                    onSaveLog = { viewModel.onIntent(VoiceIntent.SaveLog) },
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  COMPONENTS
// ════════════════════════════════════════════════════════════════

@Composable
private fun StatusBadge(state: VoiceState, modifier: Modifier = Modifier) {
    val statusColor = when (state.connectionStatus) {
        ConnectionStatus.Disconnected -> Color(0xFFF44336)
        ConnectionStatus.Connecting, ConnectionStatus.Negotiating, ConnectionStatus.Reconnecting -> Color(0xFFFFC107)
        ConnectionStatus.Ready -> Color(0xFF4CAF50)
        ConnectionStatus.Recording -> Color(0xFFFF5252)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (state.connectionStatus == ConnectionStatus.Recording) PulsingDot(color = statusColor)
        else Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = state.connectionStatus.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (state.isAiSpeaking) { Spacer(Modifier.width(8.dp)); Text("🔊", fontSize = 12.sp) }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(0.6f, 1.3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "s")
    val alpha by infiniteTransition.animateFloat(0.8f, 0.2f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "a")
    Box(contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(16.dp).scale(scale).alpha(alpha).clip(CircleShape).background(color))
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun ApiKeyInput(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { onSubmit(text.trim()) }) { Text("OK") }
    }
}

@Composable
private fun ChatBubble(message: ConversationMessage) {
    val isUser = message.role == ConversationMessage.ROLE_USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val label = if (isUser) "🎤 You" else "🔊 Gemini"
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(bubbleColor).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(text = message.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ControlButtons(state: VoiceState, onToggleMic: () -> Unit, onStop: () -> Unit, onSaveLog: () -> Unit) {
    val isReady = state.connectionStatus == ConnectionStatus.Ready
    val isRecording = state.connectionStatus == ConnectionStatus.Recording
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        Button(onClick = onToggleMic, enabled = isReady, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
            Text("Start", color = Color.White, fontSize = 12.sp) }
        Button(onClick = onStop, enabled = isRecording, modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))) {
            Text("Stop", color = Color.White, fontSize = 12.sp) }
        OutlinedButton(onClick = onSaveLog, modifier = Modifier.weight(0.7f)) {
            Text("Log", fontSize = 12.sp) }
    }
}