// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslateScreen.kt
//
// КЛЮЧЕВЫЕ ИЗМЕНЕНИЯ v5.0:
//   1. Убран SessionLoadingOverlay (был fullscreen).
//   2. Добавлен AudioParticleBox в шапку — реакция на голос Gemini.
//   3. LanguageFlowBanner упрощён: монохром, текстовые маркеры RU/UA ↔ DE
//      без эмодзи-флагов как UI.
//   4. TranscriptBubble без флагов — только текстовые лейблы и weight.
//   5. Зелёная FAB-кнопка → крупная Material-кнопка единого стиля.
//   6. Логика detectIsGerman + isActive не тронута.
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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

    // ФИКС: Гарантируем остановку сессии при системном жесте "Назад"
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
            // ─── Inline-loader + AudioParticleBox ───
            AnimatedVisibility(
                visible = showInlineLoader,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LearnTokens.PaddingSm, bottom = LearnTokens.PaddingMd),
                ) {
                    InlineLoadingBar(modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(LearnTokens.PaddingSm))
                    AudioParticleBox(
                        playbackSync = learnCoreViewModel.audioPlaybackFlow,
                        size = 36.dp,
                    )
                }
            }

            // ─── Hero banner: RU/UA ↔ DE ───
            Spacer(Modifier.height(LearnTokens.PaddingSm))
            LanguageFlowBanner(isActive = isActive)
            Spacer(Modifier.height(LearnTokens.PaddingMd))

            // ─── AudioParticleBox + speaker status ───
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeakerStatusIndicator(
                    isActive = isActive,
                    isAiSpeaking = learnState.isAiSpeaking,
                    isMicActive = learnState.isMicActive,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(LearnTokens.PaddingSm))
                AudioParticleBox(
                    playbackSync = learnCoreViewModel.audioPlaybackFlow,
                    size = 44.dp,
                )
            }
            Spacer(Modifier.height(LearnTokens.PaddingMd))

            // ─── Транскрипт (главная зона) ───
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

            // ─── FAB микрофон ───
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
        items(messages, key = { msg -> "${msg.timestamp}_${msg.role}_${msg.text.length}" }) { msg ->
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

    val isGerman = detectIsGerman(text)
    val langCode = if (isGerman) "DE" else "RU/UA"
    val label = when {
        isUser && isGerman -> "ВЫ · DE"
        isUser && !isGerman -> "ВЫ · RU/UA"
        !isUser && isGerman -> "ПЕРЕВОД · DE"
        else -> "ПЕРЕВОД · RU/UA"
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

private val GERMAN_FUNCTION_WORDS = setOf(
    "der","die","das","den","dem","des","ein","eine","einen","einem","einer","eines",
    "ich","du","er","sie","es","wir","ihr","mich","dich","ihn","uns","euch","ihnen",
    "und","oder","aber","weil","wenn","dass","ob","sondern","denn","doch",
    "ist","sind","war","waren","habe","hat","haben","wird","werden",
    "nicht","kein","keine","mit","ohne","für","gegen","über","unter","auf","aus",
    "bei","nach","seit","vor","durch","zu","in","an","im","am","ins","ans",
    "ja","nein","auch","schon","noch","mehr","sehr","gut","heute","morgen","gestern"
)
private val ENGLISH_FUNCTION_WORDS = setOf(
    "the","a","an","is","are","was","were","i","you","he","she","it","we","they",
    "have","has","had","do","does","did","not","and","or","but","if","when","that",
    "this","these","those","with","without","for","from","to","in","on","at","by",
    "yes","no","ok","what","why","how","who","where"
)

private fun detectIsGerman(text: String): Boolean {
    if (text.isBlank()) return false
    // 1) Кириллица — точно русский.
    if (text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }) return false
    // 2) Умлауты/ß — точно немецкий.
    if (text.any { it in "äöüßÄÖÜ" }) return true
    // 3) Дискриминатор по служебным словам.
    val tokens = text.lowercase()
        .replace(Regex("[^a-z ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return false
    val deHits = tokens.count { it in GERMAN_FUNCTION_WORDS }
    val enHits = tokens.count { it in ENGLISH_FUNCTION_WORDS }
    return when {
        deHits > enHits -> true
        enHits > deHits -> false
        // Ничья — латиница без явных признаков → не показываем флаг "DE",
        // считаем "не определено" (= не немецкий, чтобы не дезинформировать).
        else -> false
    }
}
