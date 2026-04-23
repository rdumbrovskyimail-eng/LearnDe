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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.sin
import kotlin.math.sqrt

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
    val ScoreRed    = Color(0xFFFF4D6A)
    val ScoreRedDk  = Color(0xFFC7203C)
    val ScoreAmber  = Color(0xFFFFB547)
    val ScoreAmbDk  = Color(0xFFC97E00)
    val ScoreGreen  = Color(0xFF3EE6A0)
    val ScoreGreDk  = Color(0xFF0E9E6A)
    val Accent      = Color(0xFF8A7CFF)
    val AccentCy    = Color(0xFF2EE6D6)
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

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) learnCoreViewModel.onIntent(LearnCoreIntent.Start("a0_test"))
        else { Toast.makeText(context, "Для теста необходим микрофон", Toast.LENGTH_SHORT).show(); exitAndBack() }
    }

    LaunchedEffect(Unit) {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasMic) learnCoreViewModel.onIntent(LearnCoreIntent.Start("a0_test"))
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(state.finished, state.verdict) {
        if (state.finished && state.verdict != TestVerdict.NONE) {
            delay(2000)
            if (state.verdict == TestVerdict.PASSED) {
                val nextSessionId = viewModel.advanceToNextPhase()
                if (nextSessionId != null) learnCoreViewModel.onIntent(LearnCoreIntent.Start(nextSessionId))
                else onNavigateToStudy("B2_GRADUATE")
            } else onNavigateToStudy(state.phase.name)
        }
    }

    var rms by remember { mutableFloatStateOf(0f) }
    var lastTickNs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(learnCoreViewModel.audioPlaybackFlow) {
        learnCoreViewModel.audioPlaybackFlow.collect { pcm ->
            rms = computeRms16(pcm)
            lastTickNs = System.nanoTime()
        }
    }
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

    val smoothedRms by animateFloatAsState(targetValue = rms.coerceIn(0f, 1f), animationSpec = spring(dampingRatio = 0.75f, stiffness = 180f), label = "rms")

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0f to TestTheme.Bg, 0.5f to TestTheme.BgDeep, 1f to TestTheme.Bg))) {
        SerpentineField(modifier = Modifier.fillMaxSize(), intensity = smoothedRms)
        Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), radius = 1400f)))

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            TopHeader(state.phase.name, state.currentQuestion, state.totalQuestions, exitAndBack)
            Spacer(Modifier.height(14.dp))
            QuestionBoard(state.currentQuestionText ?: "Gemini формулирует следующий вопрос…", smoothedRms)
            Spacer(Modifier.weight(0.5f))
            ScoreDial(state.totalPoints, state.threshold, state.lastPoints, smoothedRms)
            Spacer(Modifier.weight(0.6f))
            FeedbackBoard(state.lastAnswerCorrect, state.lastAnswerReason, state.lastScoreRationale, smoothedRms)
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GoalChip(state.threshold, state.totalPoints)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            CurrentFunctionBar(status = fnStatus, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
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

// ... Остальные UI компоненты (SerpentineField, TopHeader, QuestionBoard, ScoreDial, FeedbackBoard, GoalChip, SpeakingBars, computeRms16, lerpColor) остаются без изменений из твоего исходного кода.
@Composable private fun SerpentineField(modifier: Modifier = Modifier, intensity: Float) { /* ... */ }
@Composable private fun TopHeader(phaseName: String, currentQuestion: Int, totalQuestions: Int, onBack: () -> Unit) { /* ... */ }
@Composable private fun QuestionBoard(questionText: String, speaking: Float) { /* ... */ }
@Composable private fun ScoreDial(points: Int, threshold: Int, lastPoints: Int?, speaking: Float) { /* ... */ }
@Composable private fun GradeBadge(progress: Float, colorTop: Color) { /* ... */ }
@Composable private fun FeedbackBoard(verdictCorrect: Boolean?, reason: String?, scoreRationale: String?, speaking: Float) { /* ... */ }
@Composable private fun FeedbackRow(label: String, body: String, accent: Color) { /* ... */ }
@Composable private fun GoalChip(threshold: Int, points: Int) { /* ... */ }
@Composable private fun SpeakingBars(accent: Color) { /* ... */ }
private fun computeRms16(pcm: ByteArray): Float { /* ... */ return 0f }
private fun lerpColor(start: Color, stop: Color, fraction: Float): Color { /* ... */ return start }
private val OvershootEasing = Easing { t -> val tension = 2.0f; val t1 = t - 1.0f; t1 * t1 * ((tension + 1) * t1 + tension) + 1.0f }