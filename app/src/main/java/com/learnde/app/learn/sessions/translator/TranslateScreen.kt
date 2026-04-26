// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.1
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslateScreen.kt
//
// ИЗМЕНЕНИЯ v5.1:
//   - detectLang теперь возвращает 4 состояния: DE / RU / UK / UNKNOWN
//   - Метка пузыря "ВЫ · DETECTING…" пока текст слишком короткий
//   - Добавлено различение RU vs UK по украинским буквам
//   - Остальная UI не тронута
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.translator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.AudioParticleBox
import com.learnde.app.presentation.learn.components.InlineLoadingBar
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    onBack: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
) {
    val learnState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = learnColors()

    val isActive = learnState.sessionId == "translator" &&
        learnState.connectionStatus != LearnConnectionStatus.Disconnected

    val activity = context as? android.app.Activity
    var showRationaleDialog by remember { mutableStateOf(false) }
    var rationaleIsPermanent by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            learnCoreViewModel.onIntent(LearnCoreIntent.Start("translator"))
        } else {
            rationaleIsPermanent = activity == null ||
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, android.Manifest.permission.RECORD_AUDIO,
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

    val showInlineLoader = learnState.isPreparingSession && learnState.transcript.isEmpty()

    androidx.activity.compose.BackHandler {
        if (isActive) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
        onBack()
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colors.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Translate,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Spacer(Modifier.width(LearnTokens.PaddingSm))
                        Column {
                            Text(
                                "Переводчик Live",
                                fontSize = LearnTokens.FontSizeTitle,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textHi,
                            )
                            Text(
                                when {
                                    isActive && learnState.isAiSpeaking -> "Озвучиваю перевод"
                                    isActive && learnState.isMicActive -> "Слушаю"
                                    isActive -> "Подключение…"
                                    else -> "Готов к запуску"
                                },
                                fontSize = 10.sp,
                                color = if (isActive) colors.accent else colors.textMid,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isActive) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Назад",
                            tint = colors.textHi,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = LearnTokens.PaddingLg),
        ) {
            AnimatedVisibility(
                visible = showInlineLoader || isActive,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LearnTokens.PaddingSm, bottom = LearnTokens.PaddingMd),
                ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = showInlineLoader,
                        transitionSpec = {
                            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                        },
                        modifier = Modifier.weight(1f),
                        label = "loaderAnim"
                    ) { isLoaderVisible: Boolean ->
                        if (isLoaderVisible) {
                            InlineLoadingBar(modifier = Modifier.fillMaxWidth())
                        } else {
                            SpeakerStatusIndicator(
                                isActive = isActive,
                                isAiSpeaking = learnState.isAiSpeaking,
                                isMicActive = learnState.isMicActive,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.width(LearnTokens.PaddingSm))

                    AudioParticleBox(
                        playbackSync = learnCoreViewModel.audioPlaybackFlow,
                        size = 36.dp,
                    )
                }
            }

            LanguageFlowBanner(isActive = isActive)
            Spacer(Modifier.height(LearnTokens.PaddingMd))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(LearnTokens.RadiusMd))
                    .background(colors.surface)
                    .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusMd))
                    .padding(LearnTokens.PaddingSm),
            ) {
                if (learnState.transcript.isEmpty()) {
                    EmptyTranscriptHint(isActive = isActive)
                } else {
                    TranscriptList(messages = learnState.transcript)
                }
            }

            Spacer(Modifier.height(LearnTokens.PaddingLg))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MainMicButton(
                    isActive = isActive,
                    onStart = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            learnCoreViewModel.onIntent(LearnCoreIntent.Start("translator"))
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStop = { learnCoreViewModel.onIntent(LearnCoreIntent.Stop) },
                )
            }

            Spacer(Modifier.height(LearnTokens.PaddingLg))
        }
    }
}

@Composable
private fun LanguageFlowBanner(isActive: Boolean) {
    val colors = learnColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusMd))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusMd))
            .padding(horizontal = LearnTokens.PaddingLg, vertical = LearnTokens.PaddingMd),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LangPill("RU/UA", "Ваш язык")

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.SwapHoriz,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier
                    .size(20.dp)
                    .scale(if (isActive) 1f else 0.9f),
            )
        }

        LangPill("DE", "Немецкий")
    }
}

@Composable
private fun LangPill(code: String, label: String) {
    val colors = learnColors()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(LearnTokens.RadiusXs))
                .background(colors.surfaceVar)
                .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusXs))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                code,
                fontSize = LearnTokens.FontSizeCaption,
                fontWeight = FontWeight.Bold,
                color = colors.textHi,
                letterSpacing = LearnTokens.CapsLetterSpacing,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = colors.textLow,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SpeakerStatusIndicator(
    isActive: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = learnColors()
    val (label, color, icon) = when {
        !isActive -> Triple("Нажмите кнопку, чтобы начать", colors.textMid, null)
        isAiSpeaking -> Triple("Озвучиваю перевод", colors.accent, Icons.Filled.VolumeUp)
        isMicActive -> Triple("Слушаю · говорите", colors.success, Icons.Filled.Mic)
        else -> Triple("Подключение…", colors.warn, null)
    }
    val bg = when {
        !isActive -> colors.surfaceVar
        isAiSpeaking -> colors.accentSoft
        isMicActive -> colors.successSoft
        else -> colors.warnSoft
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusSm))
            .background(bg)
            .padding(horizontal = LearnTokens.PaddingMd, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(LearnTokens.PaddingSm))
        }
        Text(
            label,
            fontSize = LearnTokens.FontSizeBody,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun EmptyTranscriptHint(isActive: Boolean) {
    val colors = learnColors()
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Translate,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(LearnTokens.PaddingMd))
            Text(
                if (isActive) "Говорите — переведу" else "Нажмите «Старт»",
                fontSize = LearnTokens.FontSizeBodyLarge,
                color = colors.textHi,
                fontWeight = FontWeight.SemiBold,
            )
            if (isActive) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Один говорит по-русски/украински,\nдругой — по-немецки",
                    fontSize = LearnTokens.FontSizeCaption,
                    color = colors.textMid,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Forum,
                        null,
                        tint = colors.textLow,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Диалог появится здесь",
                        fontSize = LearnTokens.FontSizeMicro,
                        color = colors.textLow,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptList(messages: List<ConversationMessage>) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(messages, key = { msg -> "${msg.timestamp}_${msg.role}" }) { msg ->
            TranscriptBubble(message = msg)
        }
    }
}

@Composable
private fun TranscriptBubble(message: ConversationMessage) {
    val colors = learnColors()
    val isUser = message.role == ConversationMessage.ROLE_USER
    val text = message.text.trim()
    if (text.isEmpty()) return

    val lang = detectLang(text)
    val langText = when (lang) {
        DetectedLang.DE -> "DE"
        DetectedLang.RU -> "RU"
        DetectedLang.UK -> "UA"
        DetectedLang.UNKNOWN -> "…"
    }
    val label = when {
        isUser -> "ВЫ · $langText"
        else -> "ПЕРЕВОД · $langText"
    }
    val bg = if (isUser) colors.accentSoft else colors.surfaceVar
    val border = if (isUser) colors.accent.copy(alpha = 0.25f) else colors.stroke
    val labelColor = if (isUser) colors.accent else colors.textMid

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(
                    RoundedCornerShape(
                        topStart = LearnTokens.RadiusSm,
                        topEnd = LearnTokens.RadiusSm,
                        bottomStart = if (isUser) LearnTokens.RadiusSm else 4.dp,
                        bottomEnd = if (isUser) 4.dp else LearnTokens.RadiusSm,
                    ),
                )
                .background(bg)
                .border(
                    LearnTokens.BorderThin,
                    border,
                    RoundedCornerShape(
                        topStart = LearnTokens.RadiusSm,
                        topEnd = LearnTokens.RadiusSm,
                        bottomStart = if (isUser) LearnTokens.RadiusSm else 4.dp,
                        bottomEnd = if (isUser) 4.dp else LearnTokens.RadiusSm,
                    ),
                )
                .padding(horizontal = LearnTokens.PaddingMd, vertical = 8.dp),
        ) {
            Text(
                label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = labelColor,
                letterSpacing = LearnTokens.CapsLetterSpacing,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text,
                fontSize = LearnTokens.FontSizeBody,
                color = colors.textHi,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun MainMicButton(
    isActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = learnColors()
    val buttonColor = if (isActive) colors.error else colors.accent

    val pulse = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.16f else 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
        ),
        label = "pulseAlpha",
    )

    Box(
        modifier = Modifier.size(112.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(buttonColor.copy(alpha = pulseAlpha)),
        )
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(buttonColor.copy(alpha = 0.15f)),
        )
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable {
                    if (isActive) onStop() else onStart()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isActive) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isActive) "Остановить" else "Запустить",
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

// ─── Language detection (4-state) ───
private enum class DetectedLang { DE, RU, UK, UNKNOWN }

private val UKR_SPECIFIC = "ієґїІЄҐЇ'ʼ"
private val GERMAN_FUNCTION_WORDS = setOf(
    "der","die","das","den","dem","des","ein","eine","einen","einem","einer","eines",
    "ich","du","er","sie","es","wir","ihr","mich","dich","ihn","uns","euch","ihnen",
    "und","oder","aber","weil","wenn","dass","ob","sondern","denn","doch",
    "ist","sind","war","waren","habe","hat","haben","wird","werden",
    "nicht","kein","keine","mit","ohne","für","gegen","über","unter","auf","aus",
    "bei","nach","seit","vor","durch","zu","in","an","im","am","ins","ans",
    "ja","nein","auch","schon","noch","mehr","sehr","gut","heute","morgen","gestern"
)

private fun detectLang(text: String): DetectedLang {
    if (text.isBlank() || text == "..." || text == "…") return DetectedLang.UNKNOWN

    val hasCyrillic = text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
    val hasUkrSpecific = text.any { it in UKR_SPECIFIC }
    val hasUmlauts = text.any { it in "äöüßÄÖÜ" }

    return when {
        hasUkrSpecific -> DetectedLang.UK
        hasCyrillic -> DetectedLang.RU
        hasUmlauts -> DetectedLang.DE
        else -> {
            // Латиница без умлаутов — проверим по словарю немецких служебных слов
            val tokens = text.lowercase()
                .replace(Regex("[^a-z ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return DetectedLang.UNKNOWN
            val deHits = tokens.count { it in GERMAN_FUNCTION_WORDS }
            if (deHits > 0) DetectedLang.DE else DetectedLang.UNKNOWN
        }
    }
}