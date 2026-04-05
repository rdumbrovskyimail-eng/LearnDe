package com.codeextractor.app.presentation.voice

import android.Manifest
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeextractor.app.domain.model.ConversationMessage
import com.codeextractor.app.presentation.avatar.AvatarScene
import com.codeextractor.app.util.resolve

@Composable
fun VoiceScreen(
    viewModel: VoiceViewModel = hiltViewModel(),
    onOpenEditor: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onIntent(VoiceIntent.ToggleMic)
        else Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
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

    val renderState by viewModel.avatarAnimator
        .renderState
        .collectAsStateWithLifecycle()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ══════════════════════════════════════════════════════
            //  TOP HALF — 3D Avatar
            // ══════════════════════════════════════════════════════
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AvatarScene(
                    modifier     = Modifier.fillMaxSize(),
                    morphWeights = renderState.morphWeights,
                    headPitch    = renderState.headPitch,
                    headYaw      = renderState.headYaw,
                    headRoll     = renderState.headRoll,
                )

                // Status overlay
                StatusBadge(
                    state    = state,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )

                // ── Кнопка редактора ──
                OutlinedButton(
                    onClick  = onOpenEditor,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.5f)
                    )
                ) {
                    Text("Edit", color = Color.White, fontSize = 11.sp)
                }
            }

            // ══════════════════════════════════════════════════════
            //  BOTTOM HALF — Chat + Controls
            // ══════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(6.dp))

                if (state.showApiKeyInput) {
                    ApiKeyInput(onSubmit = { viewModel.onIntent(VoiceIntent.SubmitApiKey(it)) })
                    Spacer(modifier = Modifier.height(6.dp))
                }

                LazyColumn(
                    state    = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
                    state        = state,
                    onToggleMic  = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onStop       = { viewModel.onIntent(VoiceIntent.ToggleMic) },
                    onSaveLog    = { viewModel.onIntent(VoiceIntent.SaveLog) },
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  COMPONENTS (без изменений)
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
        if (state.connectionStatus == ConnectionStatus.Recording) {
            PulsingDot(color = statusColor)
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text       = state.connectionStatus.label,
            color      = Color.White,
            fontSize   = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.isAiSpeaking) {
            Spacer(modifier = Modifier.width(8.dp))
            Text("🔊", fontSize = 12.sp)
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "scale",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "alpha",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(16.dp).scale(scale).alpha(alpha).clip(CircleShape).background(color))
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun ApiKeyInput(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value         = text,
            onValueChange = { text = it },
            label         = { Text("API Key") },
            singleLine    = true,
            modifier      = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { onSubmit(text.trim()) }) { Text("OK") }
    }
}

@Composable
private fun ChatBubble(message: ConversationMessage) {
    val isUser      = message.role == ConversationMessage.ROLE_USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                      else        MaterialTheme.colorScheme.secondaryContainer
    val alignment   = if (isUser) Alignment.End else Alignment.Start
    val label       = if (isUser) "🎤 You" else "🔊 Gemini"

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Text(
            text     = label,
            fontSize = 10.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text  = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ControlButtons(
    state        : VoiceState,
    onToggleMic  : () -> Unit,
    onStop       : () -> Unit,
    onSaveLog    : () -> Unit,
) {
    val isReady     = state.connectionStatus == ConnectionStatus.Ready
    val isRecording = state.connectionStatus == ConnectionStatus.Recording

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Button(
            onClick  = onToggleMic,
            enabled  = isReady,
            modifier = Modifier.weight(1f),
            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
        ) { Text("Start", color = Color.White, fontSize = 12.sp) }

        Button(
            onClick  = onStop,
            enabled  = isRecording,
            modifier = Modifier.weight(1f),
            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
        ) { Text("Stop", color = Color.White, fontSize = 12.sp) }

        OutlinedButton(
            onClick  = onSaveLog,
            modifier = Modifier.weight(0.7f),
        ) { Text("Log", fontSize = 12.sp) }
    }
}