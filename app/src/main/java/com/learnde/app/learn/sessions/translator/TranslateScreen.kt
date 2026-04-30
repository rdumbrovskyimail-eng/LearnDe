// ════════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v8.0 — clean composition, premium translation pair UI
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslateScreen.kt
//
// КЛЮЧЕВЫЕ ИЗМЕНЕНИЯ vs v7.0:
//   1. Удалён HeroParticleBackground — больше не overlay поверх контента.
//   2. Удалён RotatingRing — зелёное кольцо вокруг карточки убрано.
//   3. AudioParticleBox теперь в локальном слоте 160.dp между
//      LanguageIndicator и transcript-областью. Виден ТОЛЬКО при isActive.
//   4. Glow-свечение ограничено размером слота, blur 24.dp вместо 80.
//   5. EmptyHint центрирован вертикально, tutorial-шаги читаются.
//   6. NoiseLayer — самый нижний слой, контент рендерится поверх.
//   7. FloatingBubble заменён на TranslationPairBubble — атомарная пара
//      (оригинал + перевод) в одном пузыре с разделителем и двумя
//      цветовыми акцентами.
//   8. liveUserTranscript больше не отображается — заменён на
//      ThinkingDots до прихода готовой пары.
//   9. Direction mismatch (translatedLang=="unknown") маркируется
//      красной "?" badge возле header перевода.
// ════════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.translator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

// ═══════════════════════════════════════════════════════════
//  PALETTE
// ═══════════════════════════════════════════════════════════
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

    val BubbleUserBgTop     = Color(0xFF1E2742).copy(alpha = 0.92f)
    val BubbleUserBgBottom  = Color(0xFF161D33).copy(alpha = 0.78f)
    val BubbleHighlight     = Color(0xFFFFFFFF).copy(alpha = 0.16f)
    val BubbleShade         = Color(0xFFFFFFFF).copy(alpha = 0.02f)
    val BubbleDivider       = Color(0xFFFFFFFF).copy(alpha = 0.10f)

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

    // Корневой Box: фон → шум → контент. Никаких overlay поверх контента.
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

            // Слот частиц: появляется только при активной сессии.
            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                exit  = fadeOut(tween(300)) + shrinkVertically(tween(300)),
            ) {
                AudioParticleSlot(
                    playbackSync = learnCoreViewModel.audioPlaybackFlow,
                    isAiSpeaking = learnState.isAiSpeaking,
                    isMicActive = learnState.isMicActive,
                )
            }

            // Transcript область — занимает всё оставшееся место.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val hasContent = learnState.transcript.isNotEmpty()
                val showThinking = computeShowThinking(
                    isActive = isActive,
                    isMicActive = learnState.isMicActive,
                    isAiSpeaking = learnState.isAiSpeaking,
                    transcript = learnState.transcript,
                )

                if (!hasContent && !showThinking) {
                    EmptyHint(
                        isActive = isActive,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    FloatingTranscript(
                        messages = learnState.transcript,
                        showThinking = showThinking,
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

/**
 * ThinkingDots показываем только если последнее сообщение НЕ является
 * полностью готовой парой (или transcript пуст). Это значит:
 * пользователь говорит и мы ждём перевод.
 */
private fun computeShowThinking(
    isActive: Boolean,
    isMicActive: Boolean,
    isAiSpeaking: Boolean,
    transcript: List<ConversationMessage>,
): Boolean {
    if (!isActive || !isMicActive || isAiSpeaking) return false
    val last = transcript.lastOrNull() ?: return true
    return when (last) {
        is ConversationMessage.TranslationPair -> last.translatedText.isBlank()
        is ConversationMessage.Text -> last.role == ConversationMessage.ROLE_USER
    }
}

// ═══════════════════════════════════════════════════════════
//  NOISE LAYER — едва заметная зернистость
// ═══════════════════════════════════════════════════════════
@Composable
private fun NoiseLayer() {
    val noisePoints = remember {
        List(2000) {
            Offset(Random.nextFloat(), Random.nextFloat())
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        noisePoints.forEach { p ->
            drawCircle(
                color = TranslatorPalette.NoiseColor,
                radius = 0.6f,
                center = Offset(p.x * w, p.y * h),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  AUDIO PARTICLE SLOT — локальный, не overlay
// ═══════════════════════════════════════════════════════════
@Composable
private fun AudioParticleSlot(
    playbackSync: Flow<ByteArray>,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
) {
    val targetSize = when {
        isAiSpeaking -> 140.dp
        isMicActive  -> 120.dp
        else         -> 100.dp
    }
    val animatedSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "particleSize",
    )

    val targetGlowColor = when {
        isAiSpeaking -> TranslatorPalette.AccentSpeak
        isMicActive  -> TranslatorPalette.AccentListen
        else         -> TranslatorPalette.AccentIdle
    }
    val glowColor by animateColorAsState(
        targetValue = targetGlowColor,
        animationSpec = tween(500),
        label = "particleGlow",
    )

    val pulse = rememberInfiniteTransition(label = "particlePulse")
    val breathScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "particleBreath",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Локальное мягкое свечение — ограничено слотом.
        Box(
            modifier = Modifier
                .size(animatedSize * 1.4f)
                .scale(breathScale)
                .blur(24.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.35f),
                            glowColor.copy(alpha = 0.10f),
                            Color.Transparent,
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(animatedSize)
                .scale(breathScale),
            contentAlignment = Alignment.Center,
        ) {
            AudioParticleBox(
                playbackSync = playbackSync,
                size = animatedSize,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  TOP BAR
// ═══════════════════════════════════════════════════════════
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
                isActive && isMicActive  -> stringResource(R.string.translator_status_listening)
                isActive                 -> stringResource(R.string.translator_status_ready)
                else                     -> stringResource(R.string.translator_status_idle)
            }
            val targetStatusColor = when {
                isActive && isAiSpeaking -> TranslatorPalette.AccentSpeak
                isActive && isMicActive  -> TranslatorPalette.AccentListen
                else                     -> TranslatorPalette.TextMuted
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

// ═══════════════════════════════════════════════════════════
//  LANGUAGE INDICATOR
// ═══════════════════════════════════════════════════════════
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
private fun LangChip(code: String, label: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            code,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TranslatorPalette.TextPrimary,
            letterSpacing = 1.2.sp,
        )
        Text(
            label,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = TranslatorPalette.TextMuted,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
private fun AnimatedDivider(isActive: Boolean) {
    val transition = rememberInfiniteTransition(label = "divider")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
        ),
        label = "dividerOffset",
    )

    Row(
        modifier = Modifier.width(80.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(5) { i ->
            val alpha = if (isActive) {
                val phase = (offset + i * 0.2f) % 1f
                (kotlin.math.sin(phase * Math.PI).toFloat() * 0.7f + 0.3f).coerceIn(0.2f, 1f)
            } else 0.3f

            Box(
                modifier = Modifier
                    .size(width = 6.dp, height = 2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(TranslatorPalette.TextSecondary.copy(alpha = alpha))
            )
            if (i < 4) Spacer(Modifier.width(3.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  EMPTY STATE — центрирован, без частиц поверх
// ═══════════════════════════════════════════════════════════
@Composable
private fun EmptyHint(
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        ) {
            if (!isActive) {
                TutorialSteps()
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (isActive) stringResource(R.string.translator_hint_active_title)
                    else stringResource(R.string.translator_hint_idle_title),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TranslatorPalette.TextPrimary.copy(alpha = 0.95f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isActive) stringResource(R.string.translator_hint_active_subtitle)
                    else stringResource(R.string.translator_hint_idle_subtitle),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = TranslatorPalette.TextMuted.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TutorialSteps() {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TutorialStep(num = "1", text = stringResource(R.string.translator_tutorial_step1))
        TutorialStep(num = "2", text = stringResource(R.string.translator_tutorial_step2))
        TutorialStep(num = "3", text = stringResource(R.string.translator_tutorial_step3))
    }
}

@Composable
private fun TutorialStep(num: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                num,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TranslatorPalette.TextSecondary,
            )
        }
        Text(
            text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = TranslatorPalette.TextSecondary,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  FLOATING TRANSCRIPT
// ═══════════════════════════════════════════════════════════
@Composable
private fun FloatingTranscript(
    messages: List<ConversationMessage>,
    showThinking: Boolean,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, showThinking, messages.lastOrNull()) {
        val total = messages.size + if (showThinking) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = messages,
            key = { msg -> "${msg.timestamp}_${msg.role}" },
        ) { msg ->
            when (msg) {
                is ConversationMessage.TranslationPair -> TranslationPairBubble(pair = msg)
                is ConversationMessage.Text            -> SimpleTextBubble(message = msg)
            }
        }
        if (showThinking) {
            item(key = "thinking_dots") { ThinkingDots() }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  TRANSLATION PAIR BUBBLE — премиум, две секции в одном пузыре
// ═══════════════════════════════════════════════════════════
@Composable
private fun TranslationPairBubble(
    pair: ConversationMessage.TranslationPair,
) {
    val bubbleShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 6.dp,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(bubbleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            TranslatorPalette.BubbleUserBgTop,
                            TranslatorPalette.BubbleUserBgBottom,
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            TranslatorPalette.BubbleHighlight,
                            TranslatorPalette.BubbleShade,
                        )
                    ),
                    shape = bubbleShape,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ───── Section 1: Original ─────
            BubbleSectionHeader(
                dotColor = TranslatorPalette.AccentListen,
                labelColor = TranslatorPalette.TextSecondary,
                label = "${stringResource(R.string.translator_bubble_you)} · ${pair.originalLang.uppercase()}",
                showWarning = false,
            )
            Text(
                text = pair.originalText,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = TranslatorPalette.TextPrimary.copy(alpha = 0.92f),
                fontWeight = FontWeight.Normal,
            )

            // ───── Divider ─────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                TranslatorPalette.BubbleDivider,
                                Color.Transparent,
                            )
                        )
                    )
            )

            // ───── Section 2: Translation ─────
            val isUnknown = pair.translatedLang == "unknown"
            val translationLangCode = if (isUnknown) "?" else pair.translatedLang.uppercase()
            BubbleSectionHeader(
                dotColor = TranslatorPalette.AccentSpeak,
                labelColor = TranslatorPalette.AccentSpeak,
                label = "${stringResource(R.string.translator_bubble_translation)} · $translationLangCode",
                showWarning = isUnknown && pair.translatedText.isNotBlank(),
            )
            if (pair.translatedText.isNotBlank()) {
                Text(
                    text = pair.translatedText,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = TranslatorPalette.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                InlineThinkingDots()
            }
        }
    }
}

@Composable
private fun BubbleSectionHeader(
    dotColor: Color,
    labelColor: Color,
    label: String,
    showWarning: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label.uppercase(),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor,
            letterSpacing = 1.2.sp,
        )
        if (showWarning) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(TranslatorPalette.AccentDanger.copy(alpha = 0.15f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "?",
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TranslatorPalette.AccentDanger,
                )
            }
        }
    }
}

@Composable
private fun InlineThinkingDots() {
    val transition = rememberInfiniteTransition(label = "inlineThinking")
    val dot1 by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "id1",
    )
    val dot2 by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 200), RepeatMode.Reverse),
        label = "id2",
    )
    val dot3 by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 400), RepeatMode.Reverse),
        label = "id3",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(dot1, dot2, dot3).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(TranslatorPalette.AccentSpeak),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  SIMPLE TEXT BUBBLE — fallback для редких Text сообщений
// ═══════════════════════════════════════════════════════════
@Composable
private fun SimpleTextBubble(message: ConversationMessage.Text) {
    val text = message.text.trim()
    if (text.isEmpty()) return

    val isUser = message.role == ConversationMessage.ROLE_USER
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isUser) 18.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 18.dp,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            TranslatorPalette.BubbleUserBgTop,
                            TranslatorPalette.BubbleUserBgBottom,
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            TranslatorPalette.BubbleHighlight,
                            TranslatorPalette.BubbleShade,
                        )
                    ),
                    shape = shape,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = TranslatorPalette.TextPrimary,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  GLOBAL THINKING DOTS — пузырь "ждём перевод"
// ═══════════════════════════════════════════════════════════
@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dot1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "d1",
    )
    val dot2 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 200), RepeatMode.Reverse),
        label = "d2",
    )
    val dot3 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, delayMillis = 400), RepeatMode.Reverse),
        label = "d3",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            TranslatorPalette.BubbleUserBgTop,
                            TranslatorPalette.BubbleUserBgBottom,
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            TranslatorPalette.BubbleHighlight,
                            TranslatorPalette.BubbleShade,
                        )
                    ),
                    shape = RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            listOf(dot1, dot2, dot3).forEach { alpha ->
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(TranslatorPalette.AccentListen),
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  FLOATING MIC BUTTON
// ═══════════════════════════════════════════════════════════
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun FloatingMicButton(
    isActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val targetColor = if (isActive)
        TranslatorPalette.AccentDanger
    else
        TranslatorPalette.AccentListen

    val animatedButtonColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(400),
        label = "btnColor",
    )

    val pulse = rememberInfiniteTransition(label = "btnPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.18f else 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(1400)),
        label = "pulseAlpha",
    )

    val startCd = stringResource(R.string.cd_mic_start)
    val stopCd = stringResource(R.string.cd_mic_stop)

    Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(animatedButtonColor.copy(alpha = pulseAlpha)),
        )
        Box(
            modifier = Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(animatedButtonColor.copy(alpha = 0.18f)),
        )
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            animatedButtonColor,
                            animatedButtonColor.copy(alpha = 0.85f),
                        )
                    )
                )
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.2f),
                    CircleShape,
                )
                .clickable {
                    if (isActive) onStop() else onStart()
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isActive,
                transitionSpec = {
                    (scaleIn(tween(200)) + fadeIn(tween(200))) togetherWith
                        (scaleOut(tween(200)) + fadeOut(tween(200)))
                },
                label = "iconSwap",
            ) { active ->
                Icon(
                    if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (active) stopCd else startCd,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}