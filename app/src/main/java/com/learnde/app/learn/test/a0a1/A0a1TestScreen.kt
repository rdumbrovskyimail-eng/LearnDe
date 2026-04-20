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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Composable
fun A0a1TestScreen(
    onBack: () -> Unit,
    onNavigateToStudy: (String) -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
    viewModel: A0a1TestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

        if (hasMic) {
            learnCoreViewModel.onIntent(LearnCoreIntent.Start("a0_test"))
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Автоматическая навигация при завершении (Seamless Transition)
    LaunchedEffect(state.finished, state.verdict) {
        if (state.finished && state.verdict != TestVerdict.NONE) {
            delay(2000) // Даем 2 секунды посмотреть на финальный счет
            if (state.verdict == TestVerdict.PASSED) {
                val nextSessionId = viewModel.advanceToNextPhase()
                if (nextSessionId != null) {
                    // Успех! Идем дальше
                    learnCoreViewModel.onIntent(LearnCoreIntent.Start(nextSessionId))
                } else {
                    // Прошел все тесты (B2 окончен)
                    onNavigateToStudy("B2_GRADUATE")
                }
            } else {
                // Провал. Идем на экран обучения текущего уровня (например, A0)
                onNavigateToStudy(state.phase.name)
            }
        }
    }

    // Иммерсивный черный интерфейс
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Анимированный серый фон-визуализатор
        GrayAudioVisualizerScene(
            modifier = Modifier.fillMaxSize(),
            playbackSync = learnCoreViewModel.audioPlaybackFlow
        )

        // 2. Кнопка назад
        IconButton(
            onClick = exitAndBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .align(Alignment.TopStart)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
        }

        // 3. Шапка с описанием
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Deutsch lernen",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Перед началом учебного процесса, каждый ученик должен пройти тест на знание немецкого языка, после чего он будет перенаправлен на соответствующий уровень обучения, где он сможет продолжить новое обучение или вернуться в раздел повторения и закрепления материала.",
                fontSize = 11.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${state.phase.name}-Test",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 2.sp
                )
            }
        }

        // 4. Огромные баллы по центру
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Вопрос ${state.currentQuestion} из ${state.totalQuestions}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Градиентный счетчик, меняющий цвет от красного к зеленому в зависимости от прогресса
            val progressFrac = (state.totalPoints.toFloat() / state.threshold.toFloat()).coerceIn(0f, 1f)
            val scoreColor1 = lerpColor(Color(0xFFFF5252), Color(0xFF69F0AE), progressFrac)
            val scoreColor2 = lerpColor(Color(0xFFD32F2F), Color(0xFF00C853), progressFrac)

            AnimatedContent(
                targetState = state.totalPoints,
                transitionSpec = {
                    (scaleIn(tween(500, easing = OvershootInterpolator)) + fadeIn(tween(500))) togetherWith fadeOut(tween(300))
                }, label = "scoreAnim"
            ) { score ->
                Text(
                    text = score.toString(),
                    fontSize = 140.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = androidx.compose.ui.text.TextStyle(
                        brush = Brush.verticalGradient(listOf(scoreColor1, scoreColor2))
                    )
                )
            }
            
            // Всплывающая оценка (например +7)
            AnimatedVisibility(
                visible = state.lastPoints != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut()
            ) {
                Text(
                    text = "+${state.lastPoints}",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        // 5. Общее количество (Цель) внизу слева
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 100.dp, start = 24.dp)
        ) {
            Text(
                text = "Цель",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Text(
                text = "${state.threshold} баллов",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // 6. Статус-бар в самом низу
        CurrentFunctionBar(
            status = fnStatus,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        )
    }
}

// ════════════════════════════════════════════════════════════
//  GRAY AUDIO VISUALIZER
// ════════════════════════════════════════════════════════════
@Composable
fun GrayAudioVisualizerScene(
    modifier: Modifier = Modifier,
    playbackSync: Flow<ByteArray>
) {
    var rms by remember { mutableFloatStateOf(0f) }
    var lastDecayNanos by remember { mutableLongStateOf(0L) }

    val animatedRms by animateFloatAsState(
        targetValue = rms.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 180f),
        label = "rmsAnim"
    )

    LaunchedEffect(playbackSync) {
        playbackSync.collect { pcm ->
            rms = computeRms16(pcm).coerceIn(0f, 1f)
            lastDecayNanos = System.nanoTime()
        }
    }

    LaunchedEffect(Unit) {
        var prevFrame = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prevFrame).coerceAtLeast(0L) / 1_000_000_000f
                prevFrame = now
                val silenceNanos = now - lastDecayNanos
                if (silenceNanos > 120_000_000L) {
                    val decay = Math.pow(0.02, dt.toDouble()).toFloat()
                    rms = max(0f, rms * decay)
                }
            }
        }
    }

    // Вращение колец
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var prev = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = (now - prev) / 1_000_000_000f
                prev = now
                val speed = 20f + animatedRms * 100f
                rotation = (rotation + speed * dt) % 360f
            }
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val baseR = min(w, h) / 2f
        val pulse = 0.3f + animatedRms * 0.7f

        val rings = 6
        for (i in 0 until rings) {
            val t = i.toFloat() / rings
            // Цвет - оттенки серого, меняющие прозрачность
            val color = Color.White.copy(alpha = 0.05f + (1f - t) * 0.15f)
            val r = baseR * (0.22f + t * 0.78f) * pulse
            drawCircle(
                color = color,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = (4f + (1f - t) * 10f) * (0.6f + pulse * 0.8f))
            )
        }
        
        // Центральное свечение (мягкий белый/серый)
        val glowBrush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.1f + animatedRms * 0.3f),
                Color.Transparent
            ),
            center = Offset(cx, cy),
            radius = baseR * (0.4f + pulse * 0.6f)
        )
        drawCircle(brush = glowBrush, radius = baseR * pulse, center = Offset(cx, cy))
    }
}

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

// Плавный переход цвета
private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
    return Color(
        red = start.red + fraction * (stop.red - start.red),
        green = start.green + fraction * (stop.green - start.green),
        blue = start.blue + fraction * (stop.blue - start.blue),
        alpha = start.alpha + fraction * (stop.alpha - start.alpha)
    )
}

private val OvershootInterpolator = androidx.compose.animation.core.Easing { t ->
    val tension = 2.0f
    val t1 = t - 1.0f
    t1 * t1 * ((tension + 1) * t1 + tension) + 1.0f
}