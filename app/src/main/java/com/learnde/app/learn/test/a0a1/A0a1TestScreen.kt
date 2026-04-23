// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/test/a0a1/A0a1TestScreen.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.test.a0a1

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.CurrentFunctionBar
import com.learnde.app.presentation.learn.components.SessionLoadingOverlay
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ───────────────── ПАЛИТРА ─────────────────
private object TestTheme {
    val Bg          = Color(0xFF000000)
    val BgDeep      = Color(0xFF050607)
    val Glass       = Color(0xFF0E0F13)
    val GlassHi     = Color(0xFF15171D)
    val Stroke      = Color(0x22FFFFFF)
    val StrokeSoft  = Color(0x11FFFFFF)

    val TextHi      = Color(0xFFF2F3F7)
    val TextMid     = Color(0xFF9CA1B3)
    val TextLow     = Color(0xFF5B6173)

    // Градиент счёта: красный → янтарь → зелёный
    val ScoreRed    = Color(0xFFFF4D6A)
    val ScoreRedDk  = Color(0xFFC7203C)
    val ScoreAmber  = Color(0xFFFFB547)
    val ScoreAmbDk  = Color(0xFFC97E00)
    val ScoreGreen  = Color(0xFF3EE6A0)
    val ScoreGreDk  = Color(0xFF0E9E6A)

    val Accent      = Color(0xFF8A7CFF)
    val AccentCy    = Color(0xFF2EE6D6)

    // Серые «змейки»
    val Snake0      = Color(0xFF2A2D38)
    val Snake1      = Color(0xFF3A3F52)
    val Snake2      = Color(0xFF4C5371)
    val Snake3      = Color(0xFF5E667F)
}

@Composable
fun A0a1TestScreen(
    onBack: () -> Unit,
    onNavigateToStudy: (String) -> Unit,
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

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            learnCoreViewModel.onIntent(LearnCoreIntent.Start("a0_test"))
        } else {
            Toast.makeText(context, "Для теста необходим микрофон", Toast.LENGTH_SHORT).show()
            exitAndBack()
        }
    }

    LaunchedEffect(Unit) {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) learnCoreViewModel.onIntent(LearnCoreIntent.Start("a0_test"))
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Автоматическая навигация при завершении
    LaunchedEffect(state.finished, state.verdict) {
        if (state.finished && state.verdict != TestVerdict.NONE) {
            delay(2000)
            if (state.verdict == TestVerdict.PASSED) {
                val nextSessionId = viewModel.advanceToNextPhase()
                if (nextSessionId != null) {
                    learnCoreViewModel.onIntent(LearnCoreIntent.Start(nextSessionId))
                } else {
                    onNavigateToStudy("B2_GRADUATE")
                }
            } else {
                onNavigateToStudy(state.phase.name)
            }
        }
    }

    // ───────── Реальный RMS-поток от Gemini ─────────
    var rms by remember { mutableFloatStateOf(0f) }
    var lastTickNs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(learnCoreViewModel.audioPlaybackFlow) {
        learnCoreViewModel.audioPlaybackFlow.collect { pcm ->
            rms = computeRms16(pcm)
            lastTickNs = System.nanoTime()
        }
    }
    // Плавный спад RMS при тишине
    LaunchedEffect(Unit) {
        var prev = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prev).coerceAtLeast(0L) / 1_000_000_000f
                prev = now
                if (now - lastTickNs > 120_000_000L) {
                    val decay = Math.pow(0.02, dt.toDouble()).toFloat()
                    rms = max(0f, rms * decay)
                }
            }
        }
    }

    val smoothedRms by animateFloatAsState(
        targetValue = rms.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 180f),
        label = "rms"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to TestTheme.Bg,
                    0.5f to TestTheme.BgDeep,
                    1f to TestTheme.Bg
                )
            )
    ) {
        // ── 1. Змеевидный фон ──
        SerpentineField(
            modifier = Modifier.fillMaxSize(),
            intensity = smoothedRms
        )

        // ── 2. Мягкая виньетка ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                        radius = 1400f
                    )
                )
        )

        // ── 3. Основная компоновка ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            // Header — назад + уровень + «Вопрос X из Y»
            TopHeader(
                phaseName = state.phase.name,
                currentQuestion = state.currentQuestion,
                totalQuestions = state.totalQuestions,
                onBack = exitAndBack
            )

            Spacer(Modifier.height(14.dp))

            // Панель вопроса (табло над баллами)
            QuestionBoard(
                questionText = state.currentQuestionText
                    ?: "Gemini формулирует следующий вопрос…",
                speaking = smoothedRms
            )

            Spacer(Modifier.weight(0.5f))

            // Циферблат баллов (кольцо + огромная цифра + вспышка +N)
            ScoreDial(
                points = state.totalPoints,
                threshold = state.threshold,
                lastPoints = state.lastPoints,
                speaking = smoothedRms
            )

            Spacer(Modifier.weight(0.6f))

            // Табло разбора Gemini
            FeedbackBoard(
                verdictCorrect = state.lastAnswerCorrect,
                reason = state.lastAnswerReason,
                scoreRationale = state.lastScoreRationale,
                speaking = smoothedRms
            )

            Spacer(Modifier.height(10.dp))

            // Низ: цель слева + статус-бар
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GoalChip(threshold = state.threshold, points = state.totalPoints)
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))

            CurrentFunctionBar(
                status = fnStatus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )
        }

        // ФИНАЛ: Анимация загрузки
        AnimatedVisibility(
            visible = learnState.isPreparingSession,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            SessionLoadingOverlay()
        }
    }
}

// ════════════════════════════════════════════════════════════
//  ЗМЕЕВИДНЫЙ ФОН
//  6 серых «змей», каждая своей длины, толщины, скорости.
//  Когда Gemini говорит — скорость растёт, ширина пульсирует,
//  добавляется blur-фантом и вертикальный дрейф.
// ════════════════════════════════════════════════════════════
@Composable
private fun SerpentineField(
    modifier: Modifier = Modifier,
    intensity: Float   // 0..1, RMS голоса Gemini
) {
    // Целевая скорость: базовая 0.3, при intensity=1 ~ 1.4
    val targetSpeed = 0.3f + intensity * 1.1f
    val speed by animateFloatAsState(
        targetValue = targetSpeed,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 150f),
        label = "spd"
    )

    // Ширина штриха: базовая 1.0, при intensity=1 → 2.8
    val widthMul by animateFloatAsState(
        targetValue = 1f + intensity * 1.8f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 160f),
        label = "wdt"
    )

    // Вечная фаза движения
    val infinite = rememberInfiniteTransition(label = "serp")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Медленное «дыхание» амплитуды
    val breathe by infinite.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Вертикальный дрейф — каждая змея слегка «плавает» по Y
    val drift by infinite.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(7000, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "drift"
    )

    // Параметры 6 змей (yPct, λ, φ, speedMul, толщина-мн., цвет)
    val snakes = remember {
        listOf(
            Snake(0.09f, 300f, 0.00f, 1.00f, 1.0f, TestTheme.Snake1),
            Snake(0.22f, 220f, 1.20f, 1.35f, 0.7f, TestTheme.Snake0),
            Snake(0.36f, 380f, 2.40f, 0.80f, 1.4f, TestTheme.Snake2),
            Snake(0.52f, 260f, 3.10f, 1.15f, 0.9f, TestTheme.Snake1),
            Snake(0.70f, 340f, 4.50f, 0.65f, 1.6f, TestTheme.Snake3),
            Snake(0.88f, 200f, 5.80f, 1.45f, 0.8f, TestTheme.Snake0),
        )
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Амплитуда: базовая ~1.2% h, при intensity=1 → ~4% h * breathe
        val amp = h * (0.012f + intensity * 0.028f) * breathe

        snakes.forEach { s ->
            val baseStrokePx = (1.2f + s.thickness * 1.4f) * widthMul
            val glowStrokePx = baseStrokePx * 3.6f
            val driftPx = h * 0.012f * drift * s.thickness
            val y0 = h * s.yPct + driftPx

            val path = buildSerpentinePath(
                y0 = y0,
                wavelen = s.wavelen,
                phase = phase * speed * s.speedMul + s.phaseOffset,
                amp = amp * s.thickness,
                w = w
            )

            // Glow-фантом (ярче когда Gemini говорит)
            if (intensity > 0.05f) {
                drawPath(
                    path = path,
                    color = s.color.copy(alpha = 0.10f + intensity * 0.18f),
                    style = Stroke(
                        width = glowStrokePx,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Основная линия
            drawPath(
                path = path,
                color = s.color.copy(alpha = 0.55f + intensity * 0.30f),
                style = Stroke(
                    width = baseStrokePx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

private data class Snake(
    val yPct: Float,
    val wavelen: Float,
    val phaseOffset: Float,
    val speedMul: Float,
    val thickness: Float,
    val color: Color
)

private fun buildSerpentinePath(
    y0: Float, wavelen: Float, phase: Float,
    amp: Float, w: Float
): Path {
    val path = Path()
    val step = 6f
    val k = 2f * PI.toFloat() / wavelen
    var x = -60f
    var first = true
    while (x <= w + 60f) {
        // Двухчастотная волна — «живое» змеевидное движение
        val y = y0 +
                sin(k * x + phase) * amp +
                sin(k * 0.42f * x - phase * 0.7f) * amp * 0.4f
        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        x += step
    }
    return path
}

// ════════════════════════════════════════════════════════════
//  TOP HEADER
// ════════════════════════════════════════════════════════════
@Composable
private fun TopHeader(
    phaseName: String,
    currentQuestion: Int,
    totalQuestions: Int,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Glass-кнопка назад
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = TestTheme.Glass.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, TestTheme.Stroke),
            modifier = Modifier.size(40.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, null,
                    tint = TestTheme.TextHi,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "ТЕСТИРОВАНИЕ · $phaseName",
                color = TestTheme.TextLow,
                fontSize = 10.sp,
                letterSpacing = 2.2.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Вопрос $currentQuestion из $totalQuestions",
                color = TestTheme.TextHi,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Уровень-чип
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(100))
                .background(TestTheme.Glass.copy(alpha = 0.85f))
                .border(1.dp, TestTheme.Stroke, RoundedCornerShape(100))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "$phaseName-Test",
                color = TestTheme.TextHi,
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    // Сегментированный прогресс
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        repeat(totalQuestions.coerceAtLeast(1)) { i ->
            val done = i < currentQuestion - 1
            val active = i == currentQuestion - 1
            val color by animateColorAsState(
                targetValue = when {
                    active -> TestTheme.AccentCy
                    done -> TestTheme.Accent
                    else -> TestTheme.StrokeSoft
                },
                animationSpec = tween(400), label = "seg$i"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
//  QUESTION BOARD
// ════════════════════════════════════════════════════════════
@Composable
private fun QuestionBoard(questionText: String, speaking: Float) {
    val borderAlpha = 0.15f + speaking * 0.45f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        TestTheme.Glass.copy(alpha = 0.92f),
                        TestTheme.GlassHi.copy(alpha = 0.92f)
                    )
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        TestTheme.AccentCy.copy(alpha = borderAlpha),
                        TestTheme.Accent.copy(alpha = borderAlpha * 0.8f)
                    )
                ),
                RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        Brush.linearGradient(listOf(TestTheme.Accent, TestTheme.AccentCy)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.QuestionMark, null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "ВОПРОС ОТ GEMINI",
                color = TestTheme.TextLow,
                fontSize = 10.sp,
                letterSpacing = 1.8.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (speaking > 0.05f) SpeakingBars(TestTheme.AccentCy)
        }
        Spacer(Modifier.height(10.dp))

        // Анимированная смена текста вопроса
        AnimatedContent(
            targetState = questionText,
            transitionSpec = {
                (fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 3 }) togetherWith
                        (fadeOut(tween(250)) + slideOutVertically(tween(250)) { -it / 3 })
            },
            label = "qtext"
        ) { q ->
            Text(
                text = q,
                color = TestTheme.TextHi,
                fontSize = 18.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
//  SCORE DIAL
// ════════════════════════════════════════════════════════════
@Composable
private fun ScoreDial(
    points: Int,
    threshold: Int,
    lastPoints: Int?,
    speaking: Float
) {
    val progress = (points.toFloat() / threshold.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1.2f)

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceAtMost(1f),
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "prg"
    )

    // Цвет баллов: красный → янтарь → зелёный
    val t = progress.coerceIn(0f, 1f)
    val colorTop = when {
        t < 0.5f -> lerpColor(TestTheme.ScoreRed, TestTheme.ScoreAmber, t * 2f)
        else     -> lerpColor(TestTheme.ScoreAmber, TestTheme.ScoreGreen, (t - 0.5f) * 2f)
    }
    val colorBot = when {
        t < 0.5f -> lerpColor(TestTheme.ScoreRedDk, TestTheme.ScoreAmbDk, t * 2f)
        else     -> lerpColor(TestTheme.ScoreAmbDk, TestTheme.ScoreGreDk, (t - 0.5f) * 2f)
    }

    // Вращающиеся тики
    val infinite = rememberInfiniteTransition(label = "dial")
    val ringRot by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(24000, easing = LinearEasing)),
        label = "rot"
    )
    val pulse by infinite.animateFloat(
        0.95f, 1.05f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "plz"
    )
    val dialScale = 1f + speaking * 0.04f * (pulse - 0.95f) * 2f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        contentAlignment = Alignment.Center
    ) {
        // Canvas-кольцо
        Canvas(modifier = Modifier.size(240.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val rOuter = size.minDimension / 2f

            // Внешние тики
            rotate(ringRot, pivot = c) {
                val n = 72
                for (i in 0 until n) {
                    val a = (i / n.toFloat()) * 2f * PI.toFloat()
                    val r1 = rOuter - 3f
                    val r2 = rOuter - if (i % 6 == 0) 14f else 8f
                    drawLine(
                        color = TestTheme.Stroke.copy(alpha = if (i % 6 == 0) 0.9f else 0.5f),
                        start = Offset(c.x + cos(a) * r1, c.y + sin(a) * r1),
                        end = Offset(c.x + cos(a) * r2, c.y + sin(a) * r2),
                        strokeWidth = if (i % 6 == 0) 1.6f else 0.9f
                    )
                }
            }

            // Трасса
            val trackR = rOuter - 32f
            drawCircle(
                color = TestTheme.GlassHi,
                radius = trackR, center = c,
                style = Stroke(width = 20f)
            )

            // Прогресс-арка (sweep gradient)
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        colorBot, colorTop, TestTheme.AccentCy, colorBot
                    ),
                    center = c
                ),
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = Offset(c.x - trackR, c.y - trackR),
                size = Size(trackR * 2, trackR * 2),
                style = Stroke(width = 20f, cap = StrokeCap.Round)
            )

            // Внутреннее мягкое свечение
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colorTop.copy(alpha = 0.18f + speaking * 0.12f),
                        Color.Transparent
                    ),
                    center = c,
                    radius = trackR * 0.95f
                ),
                radius = trackR - 4f, center = c
            )

            // Маркер на конце прогресса
            val angleRad = (-90f + 360f * animatedProgress) * PI.toFloat() / 180f
            val mx = c.x + cos(angleRad) * trackR
            val my = c.y + sin(angleRad) * trackR
            drawCircle(colorTop.copy(alpha = 0.3f), 12f, Offset(mx, my))
            drawCircle(Color.White, 5.5f, Offset(mx, my))
            drawCircle(colorTop, 3.2f, Offset(mx, my))
        }

        // Центр: баллы + +N
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "ТЕКУЩИЙ БАЛЛ",
                color = TestTheme.TextLow,
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))

            AnimatedContent(
                targetState = points,
                transitionSpec = {
                    (scaleIn(tween(500, easing = OvershootEasing)) + fadeIn(tween(400))) togetherWith
                            (scaleOut(tween(250)) + fadeOut(tween(250)))
                },
                label = "score"
            ) { n ->
                Text(
                    text = n.toString(),
                    fontSize = 84.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = TextStyle(
                        brush = Brush.verticalGradient(listOf(colorTop, colorBot))
                    )
                )
            }

            Spacer(Modifier.height(2.dp))
            GradeBadge(progress = progress, colorTop = colorTop)
        }

        // Всплывающая оценка +N поверх циферблата
        AnimatedVisibility(
            visible = lastPoints != null && lastPoints != 0,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp, top = 20.dp)
        ) {
            lastPoints?.let { lp ->
                val sign = if (lp >= 0) "+" else ""
                val c = if (lp >= 0) TestTheme.ScoreGreen else TestTheme.ScoreRed
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .background(c.copy(alpha = 0.15f))
                        .border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(100))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        "$sign$lp",
                        color = c,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun GradeBadge(progress: Float, colorTop: Color) {
    val label = when {
        progress >= 1.0f -> "ЦЕЛЬ ДОСТИГНУТА"
        progress >= 0.75f -> "ПОЧТИ ТАМ"
        progress >= 0.5f -> "ХОРОШИЙ ТЕМП"
        progress >= 0.25f -> "НАБИРАЙ БАЛЛЫ"
        else -> "В НАЧАЛЕ ПУТИ"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(colorTop.copy(alpha = 0.12f))
            .border(1.dp, colorTop.copy(alpha = 0.45f), RoundedCornerShape(100))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(Modifier.size(5.dp).background(colorTop, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = colorTop,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ════════════════════════════════════════════════════════════
//  FEEDBACK BOARD
// ════════════════════════════════════════════════════════════
@Composable
private fun FeedbackBoard(
    verdictCorrect: Boolean?,
    reason: String?,
    scoreRationale: String?,
    speaking: Float
) {
    val hasContent = verdictCorrect != null && (reason != null || scoreRationale != null)

    AnimatedVisibility(
        visible = hasContent,
        enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 4 },
        exit = fadeOut(tween(200))
    ) {
        val correct = verdictCorrect == true
        val accent = if (correct) TestTheme.ScoreGreen else TestTheme.ScoreRed
        val icon = if (correct) Icons.Filled.Check else Icons.Filled.Close
        val title = if (correct) "Ответ принят" else "Есть неточность"

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(TestTheme.Glass.copy(alpha = 0.92f))
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.5f), TestTheme.Stroke)
                    ),
                    RoundedCornerShape(18.dp)
                )
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(accent.copy(alpha = 0.15f), CircleShape)
                        .border(1.dp, accent.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "РАЗБОР GEMINI",
                        color = TestTheme.TextLow,
                        fontSize = 9.sp,
                        letterSpacing = 1.8.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        title,
                        color = accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (speaking > 0.05f) SpeakingBars(accent)
            }

            if (!reason.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                FeedbackRow(label = "ПОЧЕМУ", body = reason, accent = accent)
            }
            if (!scoreRationale.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                FeedbackRow(label = "ОБОСНОВАНИЕ БАЛЛА", body = scoreRationale, accent = TestTheme.AccentCy)
            }
        }
    }
}

@Composable
private fun FeedbackRow(label: String, body: String, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TestTheme.BgDeep.copy(alpha = 0.6f))
            .border(1.dp, TestTheme.StrokeSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp).background(accent, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = TestTheme.TextLow,
                fontSize = 9.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            body,
            color = TestTheme.TextHi,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

// ════════════════════════════════════════════════════════════
//  GOAL CHIP (внизу слева)
// ════════════════════════════════════════════════════════════
@Composable
private fun GoalChip(threshold: Int, points: Int) {
    val need = (threshold - points).coerceAtLeast(0)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(TestTheme.Glass.copy(alpha = 0.85f))
            .border(1.dp, TestTheme.StrokeSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column {
            Text(
                "ЦЕЛЬ",
                color = TestTheme.TextLow,
                fontSize = 9.sp,
                letterSpacing = 1.8.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$threshold",
                    color = TestTheme.TextHi,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    " баллов",
                    color = TestTheme.TextMid,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        if (need > 0) {
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100))
                    .background(TestTheme.Accent.copy(alpha = 0.15f))
                    .border(1.dp, TestTheme.Accent.copy(alpha = 0.45f), RoundedCornerShape(100))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "осталось $need",
                    color = TestTheme.Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
//  SPEAKING BARS (мини-эквалайзер)
// ════════════════════════════════════════════════════════════
@Composable
private fun SpeakingBars(accent: Color) {
    val infinite = rememberInfiniteTransition(label = "bars")
    val heights = (0 until 4).map { i ->
        infinite.animateFloat(
            0.25f, 1f,
            infiniteRepeatable(
                tween(480 + i * 130, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "b$i"
        )
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.height(18.dp)
    ) {
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(h.value)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
//  УТИЛИТЫ
// ════════════════════════════════════════════════════════════
private fun computeRms16(pcm: ByteArray): Float {
    if (pcm.size < 2) return 0f
    val n = pcm.size / 2
    var sum = 0.0
    var i = 0
    while (i + 1 < pcm.size) {
        val lo = pcm[i].toInt() and 0xFF
        val hi = pcm[i + 1].toInt()
        val sample = (hi shl 8) or lo
        val s = if (sample >= 0x8000) sample - 0x10000 else sample
        sum += (s * s).toDouble()
        i += 2
    }
    val rms = sqrt(sum / n) / 32768.0
    return (rms * 2.8).toFloat().coerceIn(0f, 1f)
}

private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + f * (stop.red - start.red),
        green = start.green + f * (stop.green - start.green),
        blue = start.blue + f * (stop.blue - start.blue),
        alpha = start.alpha + f * (stop.alpha - start.alpha)
    )
}

private val OvershootEasing = Easing { t ->
    val tension = 2.0f
    val t1 = t - 1.0f
    t1 * t1 * ((tension + 1) * t1 + tension) + 1.0f
}