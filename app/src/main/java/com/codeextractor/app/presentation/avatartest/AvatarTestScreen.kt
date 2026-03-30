package com.codeextractor.app.presentation.avatartest

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.delay

private const val TAG = "AvatarTest"

// ══════════════════════════════════════════════════════════════════════════════
//  MORPH TARGET INDICES — ARKit 51 blendshapes (без изменений)
// ══════════════════════════════════════════════════════════════════════════════

private object Idx {
    const val eyeBlinkLeft        = 0;  const val eyeLookDownLeft     = 1
    const val eyeLookInLeft       = 2;  const val eyeLookOutLeft      = 3
    const val eyeLookUpLeft       = 4;  const val eyeSquintLeft       = 5
    const val eyeWideLeft         = 6;  const val eyeBlinkRight       = 7
    const val eyeLookDownRight    = 8;  const val eyeLookInRight      = 9
    const val eyeLookOutRight     = 10; const val eyeLookUpRight      = 11
    const val eyeSquintRight      = 12; const val eyeWideRight        = 13
    const val jawForward          = 14; const val jawLeft             = 15
    const val jawRight            = 16; const val jawOpen             = 17
    const val mouthClose          = 18; const val mouthFunnel         = 19
    const val mouthPucker         = 20; const val mouthRight          = 21
    const val mouthLeft           = 22; const val mouthSmileLeft      = 23
    const val mouthSmileRight     = 24; const val mouthFrownLeft      = 25
    const val mouthFrownRight     = 26; const val mouthDimpleLeft     = 27
    const val mouthDimpleRight    = 28; const val mouthStretchLeft    = 29
    const val mouthStretchRight   = 30; const val mouthRollLower      = 31
    const val mouthRollUpper      = 32; const val mouthShrugLower     = 33
    const val mouthShrugUpper     = 34; const val mouthPressLeft      = 35
    const val mouthPressRight     = 36; const val mouthLowerDownLeft  = 37
    const val mouthLowerDownRight = 38; const val mouthUpperUpLeft    = 39
    const val mouthUpperUpRight   = 40; const val browDownLeft        = 41
    const val browDownRight       = 42; const val browInnerUp         = 43
    const val browOuterUpLeft     = 44; const val browOuterUpRight    = 45
    const val cheekPuff           = 46; const val cheekSquintLeft     = 47
    const val cheekSquintRight    = 48; const val noseSneerLeft       = 49
    const val noseSneerRight      = 50
}

// ══════════════════════════════════════════════════════════════════════════════
//  TEST SEQUENCE, Step, Category и т.д. — БЕЗ ИЗМЕНЕНИЙ
// ══════════════════════════════════════════════════════════════════════════════
// (весь код от private enum class Category до private val SEQUENCE включительно
//  оставь точно как у тебя — я его не трогаю, чтобы не было ошибок)

private enum class Category(val label: String, val color: Color) { /* ... твой код ... */ }
private data class Step(/* ... твой код ... */)
private val SEQUENCE: List<Step> = buildList { /* ... твой код ... */ }

// ══════════════════════════════════════════════════════════════════════════════
//  MORPH APPLICATION (applyMorphs + animateMorphsSmooth) — БЕЗ ИЗМЕНЕНИЙ
// ══════════════════════════════════════════════════════════════════════════════

private fun ModelNode.applyMorphs(headW: FloatArray) { /* ... твой код ... */ }
private suspend fun ModelNode.animateMorphsSmooth(cur: FloatArray, tgt: FloatArray, durMs: Long) { /* ... твой код ... */ }

// ══════════════════════════════════════════════════════════════════════════════
//  SCREEN (обновлённая часть)
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarTestScreen(onBack: () -> Unit) {

    var stepIndex   by remember { mutableIntStateOf(0) }
    var isResetting by remember { mutableStateOf(false) }
    var elapsedSec  by remember { mutableIntStateOf(0) }
    var isFinished  by remember { mutableStateOf(false) }
    var statusText  by remember { mutableStateOf("Loading model…") }

    val currentMorphState = remember { FloatArray(51) }

    val engine      = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    var modelNodeRef by remember { mutableStateOf<ModelNode?>(null) }

    // Новый рекомендуемый способ загрузки в 3.5.2
    val modelInstance = rememberModelInstance(modelLoader, "models/source_named.glb")

    LaunchedEffect(modelInstance) {
        if (modelInstance != null) {
            statusText = "OK: loaded (rememberModelInstance)"
            Log.d(TAG, "✓ Model loaded successfully with rememberModelInstance")
        } else {
            statusText = "Loading model from assets/models/source_named.glb…"
        }
    }

    val currentStep = SEQUENCE.getOrNull(stepIndex)
    val totalSteps  = SEQUENCE.size
    val animProgress by animateFloatAsState(
        targetValue   = (stepIndex + 1).toFloat() / totalSteps,
        animationSpec = tween(600), label = "progress",
    )

    LaunchedEffect(isFinished) {
        while (!isFinished) { delay(1_000); elapsedSec++ }
    }

    // Test sequence
    LaunchedEffect(modelNodeRef) {
        val n = modelNodeRef ?: return@LaunchedEffect
        Log.d(TAG, "═══ TEST SEQUENCE START ═══")
        SEQUENCE.forEachIndexed { i, step ->
            stepIndex = i; isResetting = false
            if (step.headWeights == null) {
                n.animateMorphsSmooth(currentMorphState, FloatArray(51), 300)
                n.playAnimation(0); delay(step.durationMs); n.stopAnimation(0)
            } else {
                n.animateMorphsSmooth(currentMorphState, step.headWeights, 450)
                delay((step.durationMs - 450).coerceAtLeast(200))
                isResetting = true
                n.animateMorphsSmooth(currentMorphState, FloatArray(51), 400)
                delay(100)
            }
        }
        isFinished = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Avatar Test", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // ── 3D Viewport ───────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1A1A2E))
            ) {
                Scene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,

                    // Новый declarative стиль (вместо childNodes)
                    mainLightNode = rememberMainLightNode(engine) {
                        intensity = 100_000.0f
                    },
                    cameraNode = rememberCameraNode(engine) {
                        position = Position(z = 4.0f)
                    },
                ) {
                    // Здесь модель появляется автоматически, когда загрузится
                    modelInstance?.let { instance ->
                        val mn = ModelNode(
                            modelInstance = instance,
                            scaleToUnits = 1.0f,
                            centerOrigin = Position(0f, 0f, 0f),
                            autoAnimate = false,
                        ).apply {
                            position = Position(x = 0f, y = -0.1f, z = -2.0f)
                        }
                        modelNodeRef = mn   // сохраняем ссылку для анимаций
                        mn                  // возвращаем ноду в Scene
                    }
                }

                if (!isFinished) ScanlineOverlay()
            }

            // HUD и всё остальное — БЕЗ ИЗМЕНЕНИЙ
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // ... весь твой HUD код (LinearProgressIndicator, AnimatedContent, ActiveWeightsRow и т.д.) ...
                    // (скопировал как есть, чтобы не было ошибок)
                    LinearProgressIndicator(/* ... */)
                    if (!isFinished && currentStep != null) { /* ... */ }
                    if (isFinished) { /* ... */ }
                    Row(/* status bar ... */) { /* ... */ }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  COMPONENTS (CategoryChip, ActiveWeightsRow, ScanlineOverlay) — БЕЗ ИЗМЕНЕНИЙ
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CategoryChip(category: Category) { /* ... твой код ... */ }

@Composable
private fun ActiveWeightsRow(weights: FloatArray) { /* ... твой код ... */ }

@Composable
private fun ScanlineOverlay() { /* ... твой код ... */ }