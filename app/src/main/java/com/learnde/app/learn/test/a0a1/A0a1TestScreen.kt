package com.learnde.app.learn.test.a0a1

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.height
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.CurrentFunctionBar
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A0a1TestScreen(
    onBack: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
    viewModel: A0a1TestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val learnState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val fnStatus by learnCoreViewModel.functionStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exitAndBack: () -> Unit = {
        learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
        onBack()
    }

    // 1. Лаунчер для запроса микрофона
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            learnCoreViewModel.onIntent(LearnCoreIntent.Start("a0a1_test"))
        } else {
            Toast.makeText(context, "Для теста необходим микрофон", Toast.LENGTH_SHORT).show()
            exitAndBack()
        }
    }

    // 2. При старте экрана проверяем разрешение
    LaunchedEffect(Unit) {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        if (hasMic) {
            learnCoreViewModel.onIntent(LearnCoreIntent.Start("a0a1_test"))
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
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
        },
        bottomBar = {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                CurrentFunctionBar(status = fnStatus)
            }
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
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (learnState.connectionStatus) {
                    LearnConnectionStatus.Disconnected -> "⚪ Не подключено"
                    LearnConnectionStatus.Connecting   -> "🟡 Подключение…"
                    LearnConnectionStatus.Negotiating  -> "🟡 Настройка сессии…"
                    LearnConnectionStatus.Ready        -> "🟢 Готово · говорите"
                    LearnConnectionStatus.Recording    -> "🔴 Запись"
                    LearnConnectionStatus.Reconnecting -> "🟠 Переподключение…"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            LastAwardCard(
                points = state.lastPoints,
                feedback = state.lastFeedback,
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
            onRestart = {
                viewModel.resetUiState()
                learnCoreViewModel.onIntent(LearnCoreIntent.Restart)
            },
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
            .clip(RoundedCornerShape(18.dp))
            .background(bgCol)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        val density = LocalDensity.current
        val strokeWidth = with(density) { 14.dp.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val diameter = min(w, h * 2f)
            val rect = androidx.compose.ui.geometry.Rect(
                offset = Offset((w - diameter) / 2f, h - diameter / 2f - 6.dp.toPx()),
                size = Size(diameter, diameter)
            )
            // Track
            drawArc(
                color = trackCol,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            // Progress
            val sweep = 180f * progress
            drawArc(
                brush = Brush.sweepGradient(
                    0f to primary.copy(alpha = 0.85f),
                    1f to primary,
                    center = rect.center
                ),
                startAngle = 180f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            // Threshold tick
            val thrFrac = threshold.toFloat() / maxScore.toFloat()
            val thrAngle = Math.PI * (1.0 + thrFrac)
            val rx = rect.center.x + (rect.width / 2f) * cos(thrAngle).toFloat()
            val ry = rect.center.y + (rect.height / 2f) * sin(thrAngle).toFloat()
            val rxIn = rect.center.x + (rect.width / 2f - strokeWidth) * cos(thrAngle).toFloat()
            val ryIn = rect.center.y + (rect.height / 2f - strokeWidth) * sin(thrAngle).toFloat()
            drawLine(thrCol, Offset(rx, ry), Offset(rxIn, ryIn), strokeWidth = 3.dp.toPx())
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
private fun LastAwardCard(points: Int?, feedback: String?, questionIndex: Int) {
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
                        feedback ?: when (points) {
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
