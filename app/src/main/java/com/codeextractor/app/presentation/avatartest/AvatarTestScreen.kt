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
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberMainLightNode
import kotlinx.coroutines.delay

private const val TAG = "AvatarTest"

// ══════════════════════════════════════════════════════════════════════════════
//  MORPH TARGET INDICES — ARKit 51 blendshapes
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
//  TEST SEQUENCE
// ══════════════════════════════════════════════════════════════════════════════

private enum class Category(val label: String, val color: Color) {
    EYES("Eyes", Color(0xFF1565C0)), BROWS("Brows", Color(0xFF6A1B9A)),
    JAW("Jaw", Color(0xFF00695C)), MOUTH("Mouth", Color(0xFF2E7D32)),
    CHEEKS("Cheeks", Color(0xFF795548)), NOSE("Nose", Color(0xFF607D8B)),
    EMOTION("Emotion", Color(0xFFC62828)), VISEME("Viseme", Color(0xFF0097A7)),
    COMBO("Combo", Color(0xFFAD1457)), STRESS("Stress", Color(0xFFFF6F00)),
    ANIMATION("Animation", Color(0xFF37474F)),
}

private data class Step(
    val label: String, val category: Category,
    val headWeights: FloatArray? = FloatArray(51), val durationMs: Long = 1_800,
) {
    companion object {
        fun head(label: String, cat: Category, dur: Long = 1_800, block: FloatArray.() -> Unit): Step {
            val w = FloatArray(51); w.block(); return Step(label, cat, w, dur)
        }
        fun anim(label: String, dur: Long = 20_500): Step =
            Step(label, Category.ANIMATION, headWeights = null, durationMs = dur)
    }
}

private val SEQUENCE: List<Step> = buildList {
    add(Step.head("Blink Both", Category.EYES) { this[Idx.eyeBlinkLeft]=1f; this[Idx.eyeBlinkRight]=1f })
    add(Step.head("Look Left", Category.EYES) { this[Idx.eyeLookOutLeft]=1f; this[Idx.eyeLookInRight]=1f })
    add(Step.head("Look Up", Category.EYES) { this[Idx.eyeLookUpLeft]=1f; this[Idx.eyeLookUpRight]=1f })
    add(Step.head("Wide Eyes", Category.EYES) { this[Idx.eyeWideLeft]=1f; this[Idx.eyeWideRight]=1f })
    add(Step.head("Brow Down", Category.BROWS) { this[Idx.browDownLeft]=1f; this[Idx.browDownRight]=1f })
    add(Step.head("Brow Inner Up", Category.BROWS) { this[Idx.browInnerUp]=1f })
    add(Step.head("Jaw Open", Category.JAW) { this[Idx.jawOpen]=1f })
    add(Step.head("Jaw Forward", Category.JAW) { this[Idx.jawForward]=1f })
    add(Step.head("Smile", Category.MOUTH) { this[Idx.mouthSmileLeft]=1f; this[Idx.mouthSmileRight]=1f })
    add(Step.head("Frown", Category.MOUTH) { this[Idx.mouthFrownLeft]=1f; this[Idx.mouthFrownRight]=1f })
    add(Step.head("Pucker", Category.MOUTH) { this[Idx.mouthPucker]=1f })
    add(Step.head("Funnel", Category.MOUTH) { this[Idx.mouthFunnel]=1f })
    add(Step.head("Cheek Puff", Category.CHEEKS) { this[Idx.cheekPuff]=1f })
    add(Step.head("Nose Sneer", Category.NOSE) { this[Idx.noseSneerLeft]=1f; this[Idx.noseSneerRight]=1f })
    add(Step.head("Happy", Category.EMOTION, dur=2500) {
        this[Idx.mouthSmileLeft]=0.9f; this[Idx.mouthSmileRight]=0.9f
        this[Idx.cheekSquintLeft]=0.6f; this[Idx.cheekSquintRight]=0.6f
    })
    add(Step.head("Angry", Category.EMOTION, dur=2500) {
        this[Idx.browDownLeft]=1f; this[Idx.browDownRight]=1f
        this[Idx.noseSneerLeft]=0.6f; this[Idx.noseSneerRight]=0.6f; this[Idx.jawForward]=0.3f
    })
    add(Step.head("Surprised", Category.EMOTION, dur=2500) {
        this[Idx.eyeWideLeft]=0.9f; this[Idx.eyeWideRight]=0.9f
        this[Idx.browInnerUp]=0.8f; this[Idx.jawOpen]=0.6f
    })
    add(Step.head("Viseme: AA", Category.VISEME, dur=1200) { this[Idx.jawOpen]=0.7f })
    add(Step.head("Viseme: OO", Category.VISEME, dur=1200) { this[Idx.mouthFunnel]=0.8f; this[Idx.mouthPucker]=0.5f })
    add(Step.head("Stress: Max All", Category.STRESS, dur=2500) { for(i in 0 until 51) this[i]=1f })
    add(Step.anim("Built-in Anim"))
}

// ══════════════════════════════════════════════════════════════════════════════
//  MORPH APPLICATION
// ══════════════════════════════════════════════════════════════════════════════

private fun ModelNode.applyMorphs(headW: FloatArray) {
    val instance = modelInstance ?: return
    val rm = engine.renderableManager
    val teethW = floatArrayOf(headW[Idx.jawForward], headW[Idx.jawLeft], headW[Idx.jawRight], headW[Idx.jawOpen], headW[Idx.mouthClose])
    val eyeLW = floatArrayOf(headW[Idx.eyeLookDownLeft], headW[Idx.eyeLookInLeft], headW[Idx.eyeLookOutLeft], headW[Idx.eyeLookUpLeft])
    val eyeRW = floatArrayOf(headW[Idx.eyeLookDownRight], headW[Idx.eyeLookInRight], headW[Idx.eyeLookOutRight], headW[Idx.eyeLookUpRight])
    val renderables = instance.entities.filter { rm.hasComponent(it) }.map { rm.getInstance(it) }
    var fourIdx = 0
    renderables.forEach { ri ->
        val count = rm.getMorphTargetCount(ri)
        if (count <= 0) return@forEach
        val w = when (count) {
            51 -> headW; 5 -> teethW
            4 -> { val r = if (fourIdx == 0) eyeLW else eyeRW; fourIdx++; r }
            else -> return@forEach
        }
        try { rm.setMorphWeights(ri, w, 0) } catch (_: Exception) {}
    }
}

private suspend fun ModelNode.animateMorphsSmooth(cur: FloatArray, tgt: FloatArray, durMs: Long) {
    val start = System.currentTimeMillis()
    val tmp = FloatArray(51)
    while (true) {
        val frac = ((System.currentTimeMillis() - start).toFloat() / durMs).coerceAtMost(1f)
        val t = if (frac < 0.5f) 2f * frac * frac else 1f - (-2f * frac + 2f).let { it * it } / 2f
        for (i in 0 until 51) tmp[i] = cur[i] + (tgt[i] - cur[i]) * t
        applyMorphs(tmp)
        if (frac >= 1f) { tgt.copyInto(cur); break }
        delay(16)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  SCREEN
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarTestScreen(onBack: () -> Unit) {

    var stepIndex   by remember { mutableIntStateOf(0) }
    var isResetting by remember { mutableStateOf(false) }
    var elapsedSec  by remember { mutableIntStateOf(0) }
    var isFinished  by remember { mutableStateOf(false) }
    var statusText  by remember { mutableStateOf("Init…") }

    val currentMorphState = remember { FloatArray(51) }

    val engine      = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    var modelNodeRef by remember { mutableStateOf<ModelNode?>(null) }

    // ═══════════════════════════════════════════════════════════════
    //  Загрузка модели с полной диагностикой
    //  
    //  Подход 1: createModelInstance("models/source_named.glb")
    //  Подход 2: createModel() + createInstance() (двухступенчатый)
    //  
    //  Вся диагностика выводится в Logcat с тегом "AvatarTest"
    //  И дублируется в statusText на экране
    // ═══════════════════════════════════════════════════════════════

    val childNodes = rememberNodes {
        Log.d(TAG, "═══ rememberNodes START ═══")
        Log.d(TAG, "engine=$engine, modelLoader=$modelLoader")

        // ── Подход 1: createModelInstance (одношаговый) ──
        try {
            Log.d(TAG, "Trying createModelInstance(\"models/source_named.glb\")…")
            val instance = modelLoader.createModelInstance(
                assetFileLocation = "models/source_named.glb"
            )
            Log.d(TAG, "createModelInstance result: $instance")
            if (instance != null) {
                val mn = ModelNode(
                    modelInstance = instance,
                    scaleToUnits  = 1.0f,
                    centerOrigin  = Position(0f, 0f, 0f),
                    autoAnimate   = false,
                ).apply {
                    position = Position(x = 0f, y = -0.1f, z = -2.0f)
                }
                add(mn)
                modelNodeRef = mn
                statusText = "OK: loaded"
                Log.d(TAG, "✓ Model loaded OK, node added")
                return@rememberNodes
            } else {
                Log.e(TAG, "✗ createModelInstance returned NULL")
                statusText = "null → trying fallback…"
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ createModelInstance EXCEPTION", e)
            statusText = "ex1: ${e::class.simpleName}: ${e.message}"
        }

        // ── Подход 2: createModel + createInstance (двухступенчатый) ──
        try {
            Log.d(TAG, "Trying createModel(\"models/source_named.glb\")…")
            val model = modelLoader.createModel(
                assetFileLocation = "models/source_named.glb"
            )
            Log.d(TAG, "createModel result: $model")
            if (model != null) {
                Log.d(TAG, "Trying createInstance(model)…")
                val instance = modelLoader.createInstance(model)
                Log.d(TAG, "createInstance result: $instance")
                if (instance != null) {
                    val mn = ModelNode(
                        modelInstance = instance,
                        scaleToUnits  = 1.0f,
                        centerOrigin  = Position(0f, 0f, 0f),
                        autoAnimate   = false,
                    ).apply {
                        position = Position(x = 0f, y = -0.1f, z = -2.0f)
                    }
                    add(mn)
                    modelNodeRef = mn
                    statusText = "OK: fallback"
                    Log.d(TAG, "✓ Fallback loaded OK")
                    return@rememberNodes
                } else {
                    Log.e(TAG, "✗ createInstance returned NULL")
                    statusText = "inst null"
                }
            } else {
                Log.e(TAG, "✗ createModel returned NULL")
                statusText = "model null"
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Fallback EXCEPTION", e)
            statusText = "ex2: ${e::class.simpleName}: ${e.message}"
        }

        // ── Подход 3: Без models/ префикса ──
        try {
            Log.d(TAG, "Trying createModelInstance(\"source_named.glb\")…")
            val instance = modelLoader.createModelInstance(
                assetFileLocation = "source_named.glb"
            )
            Log.d(TAG, "No-prefix result: $instance")
            if (instance != null) {
                val mn = ModelNode(
                    modelInstance = instance,
                    scaleToUnits  = 1.0f,
                    centerOrigin  = Position(0f, 0f, 0f),
                    autoAnimate   = false,
                ).apply {
                    position = Position(x = 0f, y = -0.1f, z = -2.0f)
                }
                add(mn)
                modelNodeRef = mn
                statusText = "OK: no-prefix"
                Log.d(TAG, "✓ No-prefix loaded OK")
                return@rememberNodes
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ No-prefix EXCEPTION", e)
        }

        statusText = "FAIL: all 3 attempts"
        Log.e(TAG, "═══ ALL LOADING ATTEMPTS FAILED ═══")
    }

    val currentStep = SEQUENCE.getOrNull(stepIndex)
    val totalSteps  = SEQUENCE.size
    val animProgress by animateFloatAsState(
        targetValue   = (stepIndex + 1).toFloat() / totalSteps,
        animationSpec = tween(600), label = "progress",
    )

    // Timer
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
                    modifier   = Modifier.fillMaxSize(),
                    engine     = engine,
                    modelLoader = modelLoader,
                    childNodes = childNodes,
                    // Явно задаём свет — SceneView 2.2.1 может не создать
                    // дефолтный свет автоматически
                    mainLightNode = rememberMainLightNode(engine) {
                        intensity = 100_000.0f
                    },
                    // Камера: z=4 смотрит на модель на z=-2 → расстояние 6 юнитов
                    cameraNode = rememberCameraNode(engine) {
                        position = Position(z = 4.0f)
                    },
                )

                if (!isFinished) ScanlineOverlay()
            }

            // ── HUD ───────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(
                        progress   = { animProgress },
                        modifier   = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        strokeCap  = StrokeCap.Round,
                        color      = currentStep?.category?.color ?: MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )

                    if (!isFinished && currentStep != null) {
                        AnimatedContent(
                            targetState = stepIndex,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label = "step",
                        ) { idx ->
                            val step = SEQUENCE.getOrNull(idx) ?: return@AnimatedContent
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CategoryChip(step.category)
                                Text(
                                    text = if (isResetting) "Reset…" else step.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        if (currentStep.headWeights != null && !isResetting) {
                            ActiveWeightsRow(currentStep.headWeights)
                        }
                    }

                    if (isFinished) {
                        Text("All $totalSteps steps complete", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                    }

                    // ── Status bar с полной диагностикой ──────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Step ${(stepIndex + 1).coerceAtMost(totalSteps)} / $totalSteps",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (statusText.startsWith("OK")) Color(0xFF2E7D32)
                                    else if (statusText.contains("FAIL") || statusText.contains("null") || statusText.contains("ex")) Color(0xFFD32F2F)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(
                            text = "${elapsedSec}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  COMPONENTS
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CategoryChip(category: Category) {
    Surface(shape = RoundedCornerShape(6.dp), color = category.color.copy(alpha = 0.15f)) {
        Text(category.label, color = category.color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun ActiveWeightsRow(weights: FloatArray) {
    val active = weights.indices.filter { weights[it] > 0.001f }.take(8).map { it to weights[it] }
    if (active.isEmpty()) return
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((idx, value) in active) {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("#$idx=${"%.2f".format(value)}", fontSize = 10.sp,
                     color = MaterialTheme.colorScheme.onSecondaryContainer,
                     modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun ScanlineOverlay() {
    val inf = rememberInfiniteTransition(label = "scan")
    val alpha by inf.animateFloat(0f, 0.06f,
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF00E5FF).copy(alpha = alpha)))
}
