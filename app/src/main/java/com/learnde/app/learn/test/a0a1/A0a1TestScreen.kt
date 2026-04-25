// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/learn/test/a0a1/A0a1TestScreen.kt
//
// КЛЮЧЕВЫЕ ИЗМЕНЕНИЯ v5.0:
//   1. Полностью убрана тёмная "космическая" тема + serpentines
//      → используем единый LearnColors (light/dark по системе).
//   2. Убран SessionLoadingOverlay → ничего не появляется при подготовке
//      (тест просто стартует с placeholder'ом).
//   3. ScoreDial превращён в компактный ScoreCircle (140dp).
//   4. FeedbackBoard минимизирован и встроен в общий стиль.
//   5. Без эмодзи как UI; всё через Material иконки и weight-контраст.
//   6. Совместимая с системной темой реализация.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.test.a0a1

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QuestionMark
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.AudioParticleBox
import com.learnde.app.presentation.learn.components.CurrentFunctionBar
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.Plural
import com.learnde.app.presentation.learn.theme.learnColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun A0a1TestScreen(
    onBack: () -> Unit,
    onNavigateToStudy: (String) -> Unit,
    onNavigateToRoute: (String) -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
    viewModel: A0a1TestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val learnState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val fnStatus by learnCoreViewModel.functionStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = learnColors()

    val exitAndBack: () -> Unit = {
        learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
        onBack()
    }

    androidx.activity.compose.BackHandler(onBack = exitAndBack)

    val activity = context as? android.app.Activity
    var showRationaleDialog by remember { mutableStateOf(false) }
    var rationaleIsPermanent by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            learnCoreViewModel.onIntent(LearnCoreIntent.Start("a0_test"))
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
            onDismiss = {
                showRationaleDialog = false
                exitAndBack()
            },
            onRequestAgain = {
                showRationaleDialog = false
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            context = context,
        )
    }

    LaunchedEffect(Unit) {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) learnCoreViewModel.onIntent(LearnCoreIntent.Start("a0_test"))
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(state.finished, state.verdict) {
        if (state.finished && state.verdict != TestVerdict.NONE) {
            delay(1800)
            if (state.verdict == TestVerdict.PASSED) {
                learnCoreViewModel.onIntent(LearnCoreIntent.Stop)

                // ФИКС: Ждем фактического отключения вместо хардкодного delay(400),
                // чтобы избежать гонки (race condition) между Stop и Start.
                androidx.compose.runtime.snapshotFlow { learnCoreViewModel.state.value.connectionStatus }
                    .first { it == com.learnde.app.learn.core.LearnConnectionStatus.Disconnected }

                when (val step = viewModel.advanceToNextPhase()) {
                    is A0a1TestViewModel.TestNextStep.StartSession ->
                        learnCoreViewModel.onIntent(LearnCoreIntent.Start(step.sessionId))
                    is A0a1TestViewModel.TestNextStep.NavigateRoute ->
                        onNavigateToRoute(step.route)
                    A0a1TestViewModel.TestNextStep.Graduated ->
                        onNavigateToStudy("B2_GRADUATE")
                }
            } else {
                learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                onNavigateToStudy(state.phase.name)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = LearnTokens.PaddingLg)
                .padding(top = 4.dp),
        ) {
            Spacer(Modifier.height(LearnTokens.PaddingSm))

            // ─── Header ───
            TopHeader(
                phaseName = state.phase.name,
                currentQuestion = state.currentQuestion,
                totalQuestions = state.totalQuestions,
                onBack = exitAndBack,
            )

            Spacer(Modifier.height(LearnTokens.PaddingMd))

            // ─── Question card + AudioParticleBox ───
            Row(verticalAlignment = Alignment.Top) {
                QuestionCard(
                    questionText = state.currentQuestionText
                        ?: "Подождите, вопрос подбирается…",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(LearnTokens.PaddingSm))
                AudioParticleBox(
                    playbackSync = learnCoreViewModel.audioPlaybackFlow,
                    size = 56.dp,
                )
            }

            Spacer(Modifier.weight(0.4f))

            // ─── ScoreCircle ───
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ScoreCircle(
                    points = state.totalPoints,
                    threshold = state.threshold,
                    lastPoints = state.lastPoints,
                )
            }

            Spacer(Modifier.weight(0.4f))

            // ─── FeedbackCard ───
            FeedbackCard(
                verdictCorrect = state.lastAnswerCorrect,
                reason = state.lastAnswerReason,
                scoreRationale = state.lastScoreRationale,
            )

            Spacer(Modifier.height(LearnTokens.PaddingMd))

            // ─── Goal chip ───
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalChip(threshold = state.threshold, points = state.totalPoints)
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(LearnTokens.PaddingSm))

            CurrentFunctionBar(
                status = fnStatus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )

            Spacer(Modifier.height(LearnTokens.PaddingSm))
        }
    }
}

@Composable
private fun TopHeader(
    phaseName: String,
    currentQuestion: Int,
    totalQuestions: Int,
    onBack: () -> Unit,
) {
    val colors = learnColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, null,
                tint = colors.textHi,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(4.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Тестирование · $phaseName",
                color = colors.textLow,
                fontSize = LearnTokens.FontSizeMicro,
                letterSpacing = LearnTokens.CapsLetterSpacing,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Вопрос $currentQuestion из $totalQuestions",
                color = colors.textHi,
                fontSize = LearnTokens.FontSizeBodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(LearnTokens.RadiusXs))
                .background(colors.accentSoft)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                "$phaseName-Test",
                color = colors.accent,
                fontSize = LearnTokens.FontSizeMicro,
                letterSpacing = LearnTokens.CapsLetterSpacing,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        repeat(totalQuestions.coerceAtLeast(1)) { i ->
            val done = i < currentQuestion - 1
            val active = i == currentQuestion - 1
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (active) 4.dp else 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when {
                            active -> colors.accent
                            done -> colors.accent.copy(alpha = 0.5f)
                            else -> colors.stroke
                        },
                    ),
            )
        }
    }
}

@Composable
private fun QuestionCard(questionText: String, modifier: Modifier = Modifier) {
    val colors = learnColors()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusMd))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusMd))
            .padding(LearnTokens.PaddingMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.QuestionMark, null,
                    tint = colors.accent,
                    modifier = Modifier.size(11.dp),
                )
            }
            Spacer(Modifier.width(LearnTokens.PaddingSm))
            Text(
                "Вопрос от Gemini",
                color = colors.textLow,
                fontSize = LearnTokens.FontSizeMicro,
                letterSpacing = LearnTokens.CapsLetterSpacing,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(LearnTokens.PaddingSm))

        AnimatedContent(
            targetState = questionText,
            transitionSpec = {
                (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 }) togetherWith
                    (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 4 })
            },
            label = "qtext",
        ) { q ->
            Text(
                text = q,
                color = colors.textHi,
                fontSize = LearnTokens.FontSizeTitle,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ScoreCircle(points: Int, threshold: Int, lastPoints: Int?) {
    val colors = learnColors()
    val progress = if (threshold > 0) (points.toFloat() / threshold).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "p",
    )
    val gradeLabel = when {
        progress >= 1f -> "Цель достигнута"
        progress >= 0.75f -> "Почти там"
        progress >= 0.5f -> "Хороший темп"
        progress >= 0.25f -> "Набирай баллы"
        else -> "В начале пути"
    }

    Box(
        modifier = Modifier.size(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(160.dp)) {
            val density = 1f
            val stroke = 8.dp.toPx()
            drawArc(
                color = colors.stroke,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = colors.accent,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "ТЕКУЩИЙ БАЛЛ",
                color = colors.textLow,
                fontSize = 9.sp,
                letterSpacing = LearnTokens.CapsLetterSpacing,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            AnimatedContent(
                targetState = points,
                transitionSpec = {
                    (scaleIn(tween(400)) + fadeIn(tween(300))) togetherWith
                        (scaleOut(tween(200)) + fadeOut(tween(200)))
                },
                label = "score",
            ) { n ->
                Text(
                    text = n.toString(),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.textHi,
                )
            }
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100))
                    .background(colors.accentSoft)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    gradeLabel,
                    color = colors.accent,
                    fontSize = LearnTokens.FontSizeMicro,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        // Всплывающее +N
        AnimatedVisibility(
            visible = lastPoints != null && lastPoints != 0,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp, top = 12.dp),
        ) {
            lastPoints?.let { lp ->
                val sign = if (lp >= 0) "+" else ""
                val c = if (lp >= 0) colors.success else colors.error
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .background(c.copy(alpha = 0.15f))
                        .border(LearnTokens.BorderThin, c.copy(alpha = 0.5f), RoundedCornerShape(100))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        "$sign$lp",
                        color = c,
                        fontSize = LearnTokens.FontSizeBody,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackCard(
    verdictCorrect: Boolean?,
    reason: String?,
    scoreRationale: String?,
) {
    val colors = learnColors()
    val hasContent = verdictCorrect != null && (reason != null || scoreRationale != null)
    AnimatedVisibility(
        visible = hasContent,
        enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 },
        exit = fadeOut(tween(200)),
    ) {
        val correct = verdictCorrect == true
        val accent = if (correct) colors.success else colors.error
        val accentSoft = if (correct) colors.successSoft else colors.errorSoft
        val icon = if (correct) Icons.Filled.Check else Icons.Filled.Close
        val title = if (correct) "Ответ принят" else "Есть неточность"

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(LearnTokens.RadiusMd))
                .background(accentSoft)
                .border(LearnTokens.BorderThin, accent.copy(alpha = 0.3f), RoundedCornerShape(LearnTokens.RadiusMd))
                .padding(LearnTokens.PaddingMd),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(LearnTokens.PaddingSm))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Разбор Gemini",
                        color = colors.textLow,
                        fontSize = 9.sp,
                        letterSpacing = LearnTokens.CapsLetterSpacing,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        title,
                        color = accent,
                        fontSize = LearnTokens.FontSizeBody,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (!reason.isNullOrBlank()) {
                Spacer(Modifier.height(LearnTokens.PaddingSm))
                FeedbackRow(label = "Почему", body = reason)
            }
            if (!scoreRationale.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                FeedbackRow(label = "Обоснование балла", body = scoreRationale)
            }
        }
    }
}

@Composable
private fun FeedbackRow(label: String, body: String) {
    val colors = learnColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusXs))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label.uppercase(),
            color = colors.textLow,
            fontSize = 9.sp,
            letterSpacing = LearnTokens.CapsLetterSpacing,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            body,
            color = colors.textHi,
            fontSize = LearnTokens.FontSizeBody,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun GoalChip(threshold: Int, points: Int) {
    val colors = learnColors()
    val need = (threshold - points).coerceAtLeast(0)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(colors.surfaceVar)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusXs))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                "ЦЕЛЬ",
                color = colors.textLow,
                fontSize = 9.sp,
                letterSpacing = LearnTokens.CapsLetterSpacing,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$threshold",
                    color = colors.textHi,
                    fontSize = LearnTokens.FontSizeTitle,
                    fontWeight = FontWeight.Bold,
                )

                val pointsStr = when {
                    threshold % 100 in 11..14 -> "баллов"
                    threshold % 10 == 1 -> "балл"
                    threshold % 10 in 2..4 -> "балла"
                    else -> "баллов"
                }

                Text(
                    " $pointsStr",
                    color = colors.textMid,
                    fontSize = LearnTokens.FontSizeCaption,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 1.dp),
                )
            }
        }
        if (need > 0) {
            Spacer(Modifier.width(LearnTokens.PaddingSm))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100))
                    .background(colors.accentSoft)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    "осталось $need",
                    color = colors.accent,
                    fontSize = LearnTokens.FontSizeMicro,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
