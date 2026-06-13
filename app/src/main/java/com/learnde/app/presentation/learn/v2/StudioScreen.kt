package com.learnde.app.presentation.learn.v2

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.blind.BlindPhase
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.theme.LearnColors
import com.learnde.app.presentation.learn.v2.components.GeminiOrb
import com.learnde.app.presentation.learn.v2.components.OrbMode
import com.learnde.app.presentation.learn.v2.components.StepTimeline
import com.learnde.app.presentation.learn.v2.components.WordFocusCard
import kotlin.math.roundToInt

private const val ADAPTIVE_SESSION_ID = "a1_adaptive"

@Composable
fun StudioScreen(
    onBack: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
    vm: StudioViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val coreState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = geminiColors()

    // Толкаем снапшоты соединения/аудио в StudioViewModel.
    LaunchedEffect(coreState) { vm.pushLearnCore(coreState) }

    // Разрешение микрофона.
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            learnCoreViewModel.onIntent(LearnCoreIntent.Start(ADAPTIVE_SESSION_ID))
        } else {
            Toast.makeText(context, "Без микрофона голосовой урок невозможен", Toast.LENGTH_LONG).show()
        }
    }

    fun startCore() {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) learnCoreViewModel.onIntent(LearnCoreIntent.Start(ADAPTIVE_SESSION_ID))
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Исполнение эффектов VM.
    LaunchedEffect(Unit) {
        vm.effects.collect { effect ->
            when (effect) {
                StudioEffect.RequestStartSession -> startCore()
                StudioEffect.RequestStopSession -> learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                is StudioEffect.SendSystemText -> learnCoreViewModel.sendSystemText(effect.text)
                is StudioEffect.ShowToast -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = state.sessionActive || state.blindEnabled) {
        // При активной сессии назад = подтверждение выхода (мягко).
        if (state.blindEnabled) {
            vm.onIntent(StudioIntent.ToggleBlindMode)
        } else {
            vm.onIntent(StudioIntent.StopSession)
        }
        onBack()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = GeminiDims.screenPadding),
        ) {
            // ── ШАПКА ──
            StudioHeader(state = state, colors = colors, onBack = onBack)

            Spacer(Modifier.height(GeminiDims.sectionGap))

            // ── ЦЕНТР: orb + таймлайн ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GeminiOrb(
                        mode = orbModeFor(state),
                        size = GeminiDims.orbSize,
                    )
                    Spacer(Modifier.height(16.dp))
                    OrbCaption(state = state, colors = colors)

                    if (state.stepDots.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        StepTimeline(dots = state.stepDots, colors = colors)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (state.totalSteps > 0) "Шаг ${state.cursor.coerceAtMost(state.totalSteps)} из ${state.totalSteps}" else "",
                            color = colors.textTertiary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            // ── КАРТОЧКА ФОКУСА ──
            state.focus?.let { focus ->
                WordFocusCard(focus = focus, colors = colors)
                Spacer(Modifier.height(12.dp))
            }

            // ── ПЛАШКА ОЦЕНКИ ──
            AnimatedVisibility(
                visible = state.lastEvaluation != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                state.lastEvaluation?.let { ev ->
                    EvaluationStrip(
                        lemma = ev.lemma,
                        feedback = ev.feedback,
                        correct = ev.wasCorrect,
                        colors = colors,
                        onDispute = { vm.onIntent(StudioIntent.DisputeEvaluation(ev.lemma)) },
                        onDismiss = { vm.onIntent(StudioIntent.DismissEvaluation) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── НИЖНЯЯ ПАНЕЛЬ ──
            StudioBottomBar(
                state = state,
                colors = colors,
                onPrimary = {
                    if (state.sessionActive || state.blindEnabled) vm.onIntent(StudioIntent.StopSession)
                    else vm.onIntent(StudioIntent.StartLesson)
                },
                onReview = { vm.onIntent(StudioIntent.StartReview) },
                onSkip = { vm.onIntent(StudioIntent.SkipStep) },
                onToggleBlind = { vm.onIntent(StudioIntent.ToggleBlindMode) },
            )

            Spacer(Modifier.height(8.dp))
            ConnectionLine(state = state, colors = colors)
            Spacer(Modifier.height(8.dp))
        }

        // ── ДИАЛОГИ ──
        if (state.sessionFinished) {
            FinalDialog(
                quality = state.finalQuality ?: 0,
                feedback = state.finalFeedback.orEmpty(),
                blindActive = state.blindEnabled,
                colors = colors,
                onDismiss = { vm.onIntent(StudioIntent.DismissFinalDialog) },
            )
        }

        if (state.a1Completed) {
            A1CompletedDialog(
                colors = colors,
                onDismiss = { vm.onIntent(StudioIntent.AcknowledgeA1Completed) },
            )
        }

        state.error?.let { err ->
            ErrorDialog(message = err, colors = colors, onDismiss = { vm.onIntent(StudioIntent.Refresh) })
        }

        // Загрузка данных при первом старте.
        if (state.loading) {
            Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = colors.accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Готовим словарь A1…", color = colors.textSecondary)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Шапка с прогресс-кольцом и стриком
// ─────────────────────────────────────────────────────────────────

@Composable
private fun StudioHeader(state: StudioState, colors: LearnColors, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = colors.textSecondary)
        }

        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            Text("Немецкий A1", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${state.lemmasMastered} из ${state.totalLemmas} слов · ${state.clustersMastered}/${state.totalClusters} тем",
                color = colors.textSecondary,
                fontSize = 13.sp,
            )
        }

        // Стрик.
        if (state.streakDays > 0) {
            Surface(shape = GeminiShape.chip, color = colors.accentSoft) {
                Text(
                    "🔥 ${state.streakDays}",
                    color = colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        // Прогресс-кольцо мастерства A1.
        MasteryRing(fraction = state.masteryFraction, colors = colors)
    }
}

@Composable
private fun MasteryRing(fraction: Float, colors: LearnColors) {
    Box(Modifier.size(GeminiDims.progressRing), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(GeminiDims.progressRing)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            // Фон-трек.
            drawArc(
                color = colors.accent.copy(alpha = 0.15f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            // Прогресс.
            drawArc(
                color = colors.accent,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Text(
            "${(fraction * 100).roundToInt()}%",
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  Подпись под orb (что происходит)
// ─────────────────────────────────────────────────────────────────

@Composable
private fun OrbCaption(state: StudioState, colors: LearnColors) {
    val text = when {
        state.blindEnabled && state.blindPhase == BlindPhase.BREAK ->
            state.blindStatusLine.ifBlank { "Перерыв…" }
        state.blindEnabled && state.blindStatusLine.isNotBlank() -> state.blindStatusLine
        state.isAiSpeaking -> "Лина говорит…"
        state.isMicActive -> "Слушаю вас…"
        state.isConnecting -> "Подключаюсь…"
        state.sessionActive && state.isFlexNow -> "Свободная минутка"
        state.sessionActive -> state.lessonTitle.ifBlank { "Урок идёт" }
        else -> "Готовы продолжить?"
    }
    Text(
        text = text,
        color = colors.textPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
    )
}

// ─────────────────────────────────────────────────────────────────
//  Плашка последней оценки
// ─────────────────────────────────────────────────────────────────

@Composable
private fun EvaluationStrip(
    lemma: String,
    feedback: String,
    correct: Boolean,
    colors: LearnColors,
    onDispute: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tone = if (correct) colors.success else colors.warning
    Surface(
        shape = GeminiShape.card,
        color = tone.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(tone.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(lemma, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (feedback.isNotBlank()) {
                    Text(feedback, color = colors.textSecondary, fontSize = 13.sp)
                }
            }
            if (!correct) {
                TextButton(onClick = onDispute) { Text("Оспорить", color = colors.accent) }
            }
            TextButton(onClick = onDismiss) { Text("✕", color = colors.textTertiary) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Нижняя панель: primary pill + слепой режим + skip
// ─────────────────────────────────────────────────────────────────

@Composable
private fun StudioBottomBar(
    state: StudioState,
    colors: LearnColors,
    onPrimary: () -> Unit,
    onReview: () -> Unit,
    onSkip: () -> Unit,
    onToggleBlind: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // Ряд вспомогательных действий во время сессии.
        AnimatedVisibility(visible = state.sessionActive && !state.blindEnabled) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryPill(
                    label = "Пропустить шаг",
                    icon = { Icon(Icons.Filled.SkipNext, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp)) },
                    colors = colors,
                    modifier = Modifier.weight(1f),
                    onClick = onSkip,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Главная pill-кнопка.
            PrimaryPill(
                active = state.sessionActive || state.blindEnabled,
                blind = state.blindEnabled,
                preparing = state.isPreparing || state.isConnecting,
                colors = colors,
                modifier = Modifier.weight(1f),
                onClick = onPrimary,
            )

            // Кнопка Слепого режима.
            BlindToggle(
                enabled = state.blindEnabled,
                colors = colors,
                onClick = onToggleBlind,
            )
        }

        // Кнопка повторения, когда сессии нет и есть что повторять.
        AnimatedVisibility(visible = !state.sessionActive && !state.blindEnabled && state.dueForReview > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                SecondaryPill(
                    label = "Повторить слова (${state.dueForReview})",
                    icon = null,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onReview,
                )
            }
        }
    }
}

@Composable
private fun PrimaryPill(
    active: Boolean,
    blind: Boolean,
    preparing: Boolean,
    colors: LearnColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val label = when {
        blind -> "Остановить слепой режим"
        active -> "Завершить урок"
        else -> "Начать урок"
    }
    val bg = if (active) colors.surfaceSunken else colors.accent
    val fg = if (active) colors.textPrimary else colors.onAccent

    Surface(
        shape = GeminiShape.pill,
        color = bg,
        modifier = modifier.height(GeminiDims.pillHeight),
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (preparing) {
                CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            } else {
                Icon(
                    if (active) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BlindToggle(enabled: Boolean, colors: LearnColors, onClick: () -> Unit) {
    val bg = if (enabled) colors.accent else colors.surfaceRaised
    val fg = if (enabled) colors.onAccent else colors.textSecondary
    Surface(
        shape = CircleShape,
        color = bg,
        border = if (enabled) null else androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
        modifier = Modifier.size(GeminiDims.pillHeight),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.HeadsetMic,
                contentDescription = "Слепой режим",
                tint = fg,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun SecondaryPill(
    label: String,
    icon: (@Composable () -> Unit)?,
    colors: LearnColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = GeminiShape.pill,
        color = colors.surfaceRaised,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
        modifier = modifier.height(46.dp),
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            Text(label, color = colors.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Индикатор соединения
// ─────────────────────────────────────────────────────────────────

@Composable
private fun ConnectionLine(state: StudioState, colors: LearnColors) {
    val (dotColor, text) = when {
        state.isConnected -> colors.success to "Подключено"
        state.isConnecting -> colors.warning to "Подключение…"
        state.connection == LearnConnectionStatus.Reconnecting -> colors.warning to "Переподключение…"
        else -> colors.textTertiary to "Не подключено"
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(8.dp))
        Text(text, color = colors.textTertiary, fontSize = 12.sp)
    }
}

// ─────────────────────────────────────────────────────────────────
//  Диалоги
// ─────────────────────────────────────────────────────────────────

@Composable
private fun FinalDialog(
    quality: Int,
    feedback: String,
    blindActive: Boolean,
    colors: LearnColors,
    onDismiss: () -> Unit,
) {
    // В Слепом режиме финальный диалог НЕ блокирует — цепочка идёт дальше
    // автоматически; диалог показываем только в ручном режиме.
    if (blindActive) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отлично!", color = colors.accent) } },
        title = { Text("Урок завершён", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text("Оценка: $quality / 7", color = colors.textPrimary, fontWeight = FontWeight.Medium)
                if (feedback.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(feedback, color = colors.textSecondary)
                }
            }
        },
        containerColor = colors.surfaceRaised,
        shape = GeminiShape.cardLarge,
    )
}

@Composable
private fun A1CompletedDialog(colors: LearnColors, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ура!", color = colors.accent) } },
        title = { Text("🎉 Уровень A1 пройден!") },
        text = { Text("Вы освоили весь словарь и грамматику A1. Поздравляем — это серьёзный рубеж в немецком!", color = colors.textSecondary) },
        containerColor = colors.surfaceRaised,
        shape = GeminiShape.cardLarge,
    )
}

@Composable
private fun ErrorDialog(message: String, colors: LearnColors, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно", color = colors.accent) } },
        title = { Text("Ошибка") },
        text = { Text(message, color = colors.textSecondary) },
        containerColor = colors.surfaceRaised,
        shape = GeminiShape.cardLarge,
    )
}

// ─────────────────────────────────────────────────────────────────
//  Маппинг состояния в режим orb
// ─────────────────────────────────────────────────────────────────

private fun orbModeFor(state: StudioState): OrbMode = when {
    state.isAiSpeaking -> OrbMode.SPEAKING
    state.isMicActive -> OrbMode.LISTENING
    state.isConnecting -> OrbMode.CONNECTING
    else -> OrbMode.IDLE
}