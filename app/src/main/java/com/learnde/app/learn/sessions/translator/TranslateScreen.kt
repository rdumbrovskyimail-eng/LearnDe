package com.learnde.app.learn.sessions.translator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.R
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.AudioParticleBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.random.Random

private object TranslatorPalette {
    val BgTop      = Color(0xFF0B0F1C)
    val BgBottom   = Color(0xFF03060D)

    val AccentIdle    = Color(0xFF4A5570)
    val AccentListen  = Color(0xFF00D4AA)
    val AccentSpeak   = Color(0xFF7B6FF7)
    val AccentDanger  = Color(0xFFFF5470)

    val TextPrimary   = Color(0xFFF2F4FA)
    val TextSecondary = Color(0xFFA8B0C4)
    val TextMuted     = Color(0xFF6B7388)

    val BubbleUserBgTop    = Color(0xFF1E2742).copy(alpha = 0.92f)
    val BubbleUserBgBottom = Color(0xFF161D33).copy(alpha = 0.78f)
    val BubbleModelBgTop   = Color(0xFF1A2138).copy(alpha = 0.92f)
    val BubbleModelBgBottom = Color(0xFF12182A).copy(alpha = 0.78f)
    val BubbleHighlight    = Color(0xFFFFFFFF).copy(alpha = 0.16f)
    val BubbleShade        = Color(0xFFFFFFFF).copy(alpha = 0.02f)

    val NoiseColor = Color(0xFFFFFFFF).copy(alpha = 0.012f)
}

@Composable
fun TranslatorScreen(
    onBack: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
) {
    val learnState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val isActive = learnState.sessionId == "translator" &&
        learnState.connectionStatus != LearnConnectionStatus.Disconnected

    val activity = context as? android.app.Activity
    var showRationaleDialog by remember { mutableStateOf(false) }
    var rationaleIsPermanent by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            learnCoreViewModel.onIntent(LearnCoreIntent.Start("translator"))
        } else {
            rationaleIsPermanent = activity == null ||
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.RECORD_AUDIO,
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
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            context = context,
        )
    }

    androidx.activity.compose.BackHandler {
        if (isActive) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(TranslatorPalette.BgTop, TranslatorPalette.BgBottom)
                )
            )
    ) {
        NoiseLayer()

        HeroParticleBackground(
            playbackSync = learnCoreViewModel.audioPlaybackFlow,
            isActive = isActive,
            isAiSpeaking = learnState.isAiSpeaking,
            isMicActive = learnState.isMicActive,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            TopBar(
                onBack = {
                    if (isActive) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                    onBack()
                },
                isActive = isActive,
                isAiSpeaking = learnState.isAiSpeaking,
                isMicActive = learnState.isMicActive,
            )

            LanguageIndicator(
                isActive = isActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (learnState.transcript.isEmpty() && learnState.liveUserTranscript.isEmpty()) {
                    EmptyHint(
                        isActive = isActive,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    FloatingTranscript(
                        messages = learnState.transcript,
                        liveUserText = learnState.liveUserTranscript,
                        showThinking = isActive
                            && learnState.isMicActive
                            && !learnState.isAiSpeaking
                            && learnState.transcript.lastOrNull()?.role != ConversationMessage.ROLE_USER,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                FloatingMicButton(
                    isActive = isActive,
                    onStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val hasMic = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            learnCoreViewModel.onIntent(LearnCoreIntent.Start("translator"))
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStop = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                    },
                )
            }
        }
    }
}

@Composable
private fun NoiseLayer() {
    val noiseColor = TranslatorPalette.NoiseColor
    val noisePoints = remember {
        val points = FloatArray(4000)
        for (i in 0 until 2000) {
            points[i * 2] = Random.nextFloat()
            points[i * 2 + 1] = Random.nextFloat()
        }
        points
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val scaled = FloatArray(4000)
        for (i in 0 until 2000) {
            scaled[i * 2] = noisePoints[i * 2] * w
            scaled[i * 2 + 1] = noisePoints[i * 2 + 1] * h
        }
        drawPoints(scaled, PointMode.Points, color = noiseColor, strokeWidth = 2f)
    }
}

@Composable
private fun HeroParticleBackground(
    playbackSync: Flow<ByteArray>,
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
) {
    val targetSize = when {
        !isActive -> 220.dp
        isAiSpeaking -> 380.dp
        isMicActive -> 320.dp
        else -> 260.dp
    }

    val animatedSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "heroSize",
    )

    val pulseTransition = rememberInfiniteTransition(label = "heroPulse")
    val breathScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "heroBreath",
    )

    val targetGlowAlpha = when {
        !isActive -> 0.15f
        isAiSpeaking -> 0.55f
        isMicActive -> 0.45f
        else -> 0.25f
    }
    val glowAlpha by animateFloatAsState(
        targetValue = targetGlowAlpha,
        animationSpec = tween(600),
        label = "heroGlow",
    )

    val targetGlowColor = when {
        !isActive -> TranslatorPalette.AccentIdle
        isAiSpeaking -> TranslatorPalette.AccentSpeak
        isMicActive -> TranslatorPalette.AccentListen
        else -> TranslatorPalette.AccentIdle
    }
    val glowColor by animateColorAsState(
        targetValue = targetGlowColor,
        animationSpec = tween(600),
        label = "heroGlowColor",
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(animatedSize * 1.8f)
                .scale(breathScale)
                .blur(80.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = glowAlpha),
                            glowColor.copy(alpha = glowAlpha * 0.4f),
                            Color.Transparent,
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .size(animatedSize)
                .scale(breathScale)
                .alpha(if (isActive) 1f else 0.7f),
            contentAlignment = Alignment.Center,
        ) {
            AudioParticleBox(
                playbackSync = playbackSync,
                size = animatedSize,
            )
        }

        AnimatedVisibility(
            visible = isActive,
            enter = fadeIn(tween(600)),
            exit = fadeOut(tween(400)),
        ) {
            RotatingRing(
                size = animatedSize + 24.dp,
                color = glowColor,
                isFast = isAiSpeaking || isMicActive,
            )
        }
    }
}

@Composable
private fun RotatingRing(
    size: androidx.compose.ui.unit.Dp,
    color: Color,
    isFast: Boolean,
) {
    val rotation = rememberInfiniteTransition(label = "ringRotation")
    val angle by rotation.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isFast) 6000 else 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAngle",
    )

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = angle }
            .clip(CircleShape)
            .border(
                width = 1.dp,
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        color.copy(alpha = 0.6f),
                        color.copy(alpha = 0.2f),
                        Color.Transparent,
                        Color.Transparent,
                    )
                ),
                shape = CircleShape,
            )
            .alpha(0.7f),
    )
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = TranslatorPalette.TextPrimary,
            )
        }

        Spacer(Modifier.width(4.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.translator_title),
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = TranslatorPalette.TextPrimary,
                letterSpacing = 0.2.sp,
            )

            val statusText = when {
                isActive && isAiSpeaking -> stringResource(R.string.translator_status_speaking)
                isActive && isMicActive -> stringResource(R.string.translator_status_listening)
                isActive -> stringResource(R.string.translator_status_ready)
                else -> stringResource(R.string.translator_status_idle)
            }
            val targetStatusColor = when {
                isActive && isAiSpeaking -> TranslatorPalette.AccentSpeak
                isActive && isMicActive -> TranslatorPalette.AccentListen
                else -> TranslatorPalette.TextMuted
            }
            val statusColor by animateColorAsState(
                targetValue = targetStatusColor,
                animationSpec = tween(400),
                label = "statusColor",
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    statusText,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = statusColor,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.3.sp,
                )
            }
        }

        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun LanguageIndicator(
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.02f),
                    )
                ),
                shape = RoundedCornerShape(28.dp),
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LangChip(
            code = stringResource(R.string.translator_lang_codes_user),
            label = stringResource(R.string.translator_lang_user),
        )

        AnimatedDivider(isActive = isActive)

        LangChip(
            code = stringResource(R.string.translator_lang_code_target),
            label = stringResource(R.string.translator_lang_target),
        )
    }
}

@Composable
private fun LangChip(