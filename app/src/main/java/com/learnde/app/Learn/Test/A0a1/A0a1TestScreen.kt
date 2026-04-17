// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/Learn/Test/A0a1/A0a1TestScreen.kt
//
// Обновлено под упрощённый state:
//   • lastPoints + lastQuestionIndex вместо PointsAwarded
// ═══════════════════════════════════════════════════════════
package com.learnde.app.Learn.Test.A0a1

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A0a1TestScreen(
    onBack: () -> Unit,
    viewModel: A0a1TestViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val exitAndBack: () -> Unit = {
        viewModel.signalExit()
        onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Тест A0-A1 · Deutsch",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = exitAndBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            ScoreGauge(
                score = state.totalPoints,
                maxScore = state.maxPoints,
                threshold = state.threshold,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
            )

            Spacer(Modifier.height(12.dp))

            QuestionsProgress(
                answered = state.answeredCount,
                total = A0a1TestRegistry.TOTAL_QUESTIONS
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Проходной балл для уровня A1 — ${state.threshold} из ${state.maxPoints}.\n" +
                        "Микрофон включается автоматически — говорите, когда преподаватель задаст вопрос.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
                lineHeight = 18.sp
            )

            Spacer(Modifier.weight(1f))

            LastAwardCard(
                points = state.lastPoints,
                questionIndex = state.lastQuestionIndex
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.finished && state.verdict != TestVerdict.NONE) {
        VerdictDialog(
            verdict = state.verdict,
            score = state.totalPoints,
            maxScore = state.maxPoints,
            onRestart = { viewModel.restart() },
            onClose = exitAndBack
        )
    }
}

// ════════════════════════════════════════════════════════════
//  GAUGE
// ════════════════════════════════════════════════════════════

@Composable
private fun ScoreGauge(
    score: Int,
    maxScore: Int,
    threshold: Int,
    modifier: Modifier = Modifier
) {
    val animated by animateFloatAsState(
        targetValue = score.toFloat(),
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "gaugeAnim"
    )
    val progress = (animated / maxScore).coerceIn(0f, 1f)
    val displayInt = animated.toInt().coerceIn(0, maxScore)

    val numberPulse = remember { Animatable(1f) }
    var lastScore by remember { mutableIntStateOf(score) }
    LaunchedEffect(score) {
        if (score > lastScore) {
            numberPulse.snapTo(1.15f)
            numberPulse.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        }
        lastScore = score
    }

    val bgCol    = MaterialTheme.colorScheme.surface
    val trackCol = MaterialTheme.colorScheme.surfaceVariant
    val tickCol  = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val thrCol   = MaterialTheme.colorScheme.tertiary
    val primary  = MaterialTheme.colorScheme.primary
    val onSurf   = MaterialTheme.colorScheme.onSurface

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(24.dp))
            .background(bgCol)
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        val density = LocalDensity.current
        val strokePx = with(density) { 22.dp.toPx() }
        val tickLen  = with(density) { 8.dp.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val pad = strokePx / 2f + 4f
            val arcSize = Size(w - pad * 2f, (h - pad) * 2f)
            val arcTopLeft = Offset(pad, pad)

            drawArc(
                color = trackCol,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            val sweep = 180f * progress
            if (sweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color(0xFFE53935),
                            Color(0xFFFFB300),
                            Color(0xFF43A047)
                        ),
                        center = Offset(w / 2f, h)
                    ),
                    startAngle = 180f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }

            val centerX = w / 2f
            val centerY = h
            val outerR = (w - pad * 2f) / 2f
            val innerR = outerR - tickLen
            val labels = listOf(0, 15, 30, 45, 60)
            labels.forEach { v ->
                val frac = v.toFloat() / maxScore
                val angDeg = 180f + 180f * frac
                val angRad = Math.toRadians(angDeg.toDouble())
                val cosA = cos(angRad).toFloat()
                val sinA = sin(angRad).toFloat()
                val isThr = v == threshold
                drawLine(
                    color = if (isThr) thrCol else tickCol,
                    start = Offset(centerX + innerR * cosA, centerY + innerR * sinA),
                    end = Offset(centerX + outerR * cosA, centerY + outerR * sinA),
                    strokeWidth = if (isThr) 4f else 2f,
                    cap = StrokeCap.Round
                )
            }

            val needleAng = Math.toRadians((180f + 180f * progress).toDouble())
            val needleLen = outerR - strokePx / 2f - 12f
            val nx = centerX + needleLen * cos(needleAng).toFloat()
            val ny = centerY + needleLen * sin(needleAng).toFloat()
            drawLine(
                color = primary,
                start = Offset(centerX, centerY),
                end = Offset(nx, ny),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
            drawCircle(primary, radius = 12f, center = Offset(centerX, centerY))
            drawCircle(bgCol, radius = 5f, center = Offset(centerX, centerY))
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text(
                text = displayInt.toString(),
                fontSize = (64 * numberPulse.value).sp,
                fontWeight = FontWeight.Bold,
                color = onSurf
            )
            Text(
                text = "из $maxScore баллов",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0",  fontSize = 11.sp, color = tickCol, fontWeight = FontWeight.Medium)
            Text("$maxScore", fontSize = 11.sp, color = tickCol, fontWeight = FontWeight.Medium)
        }
    }
}

// ════════════════════════════════════════════════════════════
//  QUESTIONS PROGRESS
// ════════════════════════════════════════════════════════════

@Composable
private fun QuestionsProgress(answered: Int, total: Int) {
    val frac by animateFloatAsState(
        targetValue = (answered.toFloat() / total).coerceIn(0f, 1f),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "qProgress"
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Вопрос ${min(answered + 1, total)} из $total",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                "$answered / $total",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
    }
}

// ════════════════════════════════════════════════════════════
//  LAST AWARD
// ════════════════════════════════════════════════════════════

@Composable
private fun LastAwardCard(points: Int?, questionIndex: Int) {
    AnimatedVisibility(
        visible = points != null,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
        exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 2 }
    ) {
        if (points != null) {
            val accent = when (points) {
                3 -> Color(0xFF43A047)
                2 -> Color(0xFF7CB342)
                1 -> Color(0xFFFFB300)
                else -> Color(0xFFE53935)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(2.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "+$points",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Вопрос $questionIndex оценён",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        when (points) {
                            3 -> "Отличный ответ!"
                            2 -> "Хорошо, с небольшими ошибками"
                            1 -> "Понятно, но неполно"
                            else -> "Попробуйте следующий вопрос"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
//  VERDICT DIALOG
// ════════════════════════════════════════════════════════════

@Composable
private fun VerdictDialog(
    verdict: TestVerdict,
    score: Int,
    maxScore: Int,
    onRestart: () -> Unit,
    onClose: () -> Unit
) {
    val isA1 = verdict == TestVerdict.A1
    val accent = if (isA1) Color(0xFF43A047) else Color(0xFFFB8C00)
    val headline = if (isA1) "A1" else "A0"
    val subtitle = if (isA1)
        "Поздравляем! Ваш уровень немецкого — A1 (базовый). Вы понимаете и используете повседневные фразы."
    else
        "Ваш уровень пока — A0 (начинающий). Это отличная точка старта: продолжайте учиться, и A1 будет близко."

    AlertDialog(
        onDismissRequest = { },
        containerColor = MaterialTheme.colorScheme.surface,
        title = null,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        headline,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Ваш результат",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "$score из $maxScore",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) {
                Text("Готово", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onRestart) {
                Text("Пройти заново", color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}
