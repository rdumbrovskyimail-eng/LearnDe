package com.codeextractor.app.presentation.avatartest

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ══════════════════════════════════════════════════════════════════════════════
//  MORPH TARGET INDICES — ARKit 51 blendshapes (source_named.glb)
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
    EYES     ("Eyes",      Color(0xFF1565C0)),
    BROWS    ("Brows",     Color(0xFF6A1B9A)),
    JAW      ("Jaw",       Color(0xFF00695C)),
    MOUTH    ("Mouth",     Color(0xFF2E7D32)),
    CHEEKS   ("Cheeks",    Color(0xFF795548)),
    NOSE     ("Nose",      Color(0xFF607D8B)),
    FACE     ("Face",      Color(0xFFE65100)),
    EMOTION  ("Emotion",   Color(0xFFC62828)),
    VISEME   ("Viseme",    Color(0xFF0097A7)),
    COMBO    ("Combo",     Color(0xFFAD1457)),
    STRESS   ("Stress",    Color(0xFFFF6F00)),
    ANIMATION("Animation", Color(0xFF37474F)),
}

private data class Step(
    val label      : String,
    val category   : Category,
    val headWeights: FloatArray? = FloatArray(51),
    val durationMs : Long        = 1_800,
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

    // ── EYES ──────────────────────────────────────────────────────
    add(Step.head("Blink Both", Category.EYES) {
        this[Idx.eyeBlinkLeft] = 1f; this[Idx.eyeBlinkRight] = 1f
    })
    add(Step.head("Blink Left", Category.EYES) {
        this[Idx.eyeBlinkLeft] = 1f
    })
    add(Step.head("Blink Right", Category.EYES) {
        this[Idx.eyeBlinkRight] = 1f
    })
    add(Step.head("Look Left", Category.EYES) {
        this[Idx.eyeLookOutLeft] = 1f; this[Idx.eyeLookInRight] = 1f
    })
    add(Step.head("Look Right", Category.EYES) {
        this[Idx.eyeLookInLeft] = 1f; this[Idx.eyeLookOutRight] = 1f
    })
    add(Step.head("Look Up", Category.EYES) {
        this[Idx.eyeLookUpLeft] = 1f; this[Idx.eyeLookUpRight] = 1f
    })
    add(Step.head("Look Down", Category.EYES) {
        this[Idx.eyeLookDownLeft] = 1f; this[Idx.eyeLookDownRight] = 1f
    })
    add(Step.head("Squint Both", Category.EYES) {
        this[Idx.eyeSquintLeft] = 1f; this[Idx.eyeSquintRight] = 1f
    })
    add(Step.head("Wide Eyes", Category.EYES) {
        this[Idx.eyeWideLeft] = 1f; this[Idx.eyeWideRight] = 1f
    })

    // ── BROWS ─────────────────────────────────────────────────────
    add(Step.head("Brow Down", Category.BROWS) {
        this[Idx.browDownLeft] = 1f; this[Idx.browDownRight] = 1f
    })
    add(Step.head("Brow Inner Up", Category.BROWS) {
        this[Idx.browInnerUp] = 1f
    })
    add(Step.head("Brow Outer Up", Category.BROWS) {
        this[Idx.browOuterUpLeft] = 1f; this[Idx.browOuterUpRight] = 1f
    })
    add(Step.head("Brow Left Up", Category.BROWS) {
        this[Idx.browOuterUpLeft] = 1f; this[Idx.browDownRight] = 0.3f
    })
    add(Step.head("Brow Right Up", Category.BROWS) {
        this[Idx.browOuterUpRight] = 1f; this[Idx.browDownLeft] = 0.3f
    })

    // ── JAW ───────────────────────────────────────────────────────
    add(Step.head("Jaw Open", Category.JAW) {
        this[Idx.jawOpen] = 1f
    })
    add(Step.head("Jaw Forward", Category.JAW) {
        this[Idx.jawForward] = 1f
    })
    add(Step.head("Jaw Left", Category.JAW) {
        this[Idx.jawLeft] = 1f
    })
    add(Step.head("Jaw Right", Category.JAW) {
        this[Idx.jawRight] = 1f
    })

    // ── MOUTH ─────────────────────────────────────────────────────
    add(Step.head("Smile", Category.MOUTH) {
        this[Idx.mouthSmileLeft] = 1f; this[Idx.mouthSmileRight] = 1f
    })
    add(Step.head("Frown", Category.MOUTH) {
        this[Idx.mouthFrownLeft] = 1f; this[Idx.mouthFrownRight] = 1f
    })
    add(Step.head("Pucker", Category.MOUTH) {
        this[Idx.mouthPucker] = 1f
    })
    add(Step.head("Funnel", Category.MOUTH) {
        this[Idx.mouthFunnel] = 1f
    })
    add(Step.head("Mouth Left", Category.MOUTH) {
        this[Idx.mouthLeft] = 1f
    })
    add(Step.head("Mouth Right", Category.MOUTH) {
        this[Idx.mouthRight] = 1f
    })
    add(Step.head("Mouth Close", Category.MOUTH) {
        this[Idx.mouthClose] = 1f
    })
    add(Step.head("Roll Lower", Category.MOUTH) {
        this[Idx.mouthRollLower] = 1f
    })
    add(Step.head("Roll Upper", Category.MOUTH) {
        this[Idx.mouthRollUpper] = 1f
    })
    add(Step.head("Shrug Lower", Category.MOUTH) {
        this[Idx.mouthShrugLower] = 1f
    })
    add(Step.head("Shrug Upper", Category.MOUTH) {
        this[Idx.mouthShrugUpper] = 1f
    })
    add(Step.head("Press Both", Category.MOUTH) {
        this[Idx.mouthPressLeft] = 1f; this[Idx.mouthPressRight] = 1f
    })
    add(Step.head("Lower Down", Category.MOUTH) {
        this[Idx.mouthLowerDownLeft] = 1f; this[Idx.mouthLowerDownRight] = 1f
    })
    add(Step.head("Upper Up", Category.MOUTH) {
        this[Idx.mouthUpperUpLeft] = 1f; this[Idx.mouthUpperUpRight] = 1f
    })
    add(Step.head("Dimple Both", Category.MOUTH) {
        this[Idx.mouthDimpleLeft] = 1f; this[Idx.mouthDimpleRight] = 1f
    })
    add(Step.head("Stretch Both", Category.MOUTH) {
        this[Idx.mouthStretchLeft] = 1f; this[Idx.mouthStretchRight] = 1f
    })

    // ── CHEEKS & NOSE ────────────────────────────────────────────
    add(Step.head("Cheek Puff", Category.CHEEKS) {
        this[Idx.cheekPuff] = 1f
    })
    add(Step.head("Cheek Squint", Category.CHEEKS) {
        this[Idx.cheekSquintLeft] = 1f; this[Idx.cheekSquintRight] = 1f
    })
    add(Step.head("Nose Sneer", Category.NOSE) {
        this[Idx.noseSneerLeft] = 1f; this[Idx.noseSneerRight] = 1f
    })

    // ── EMOTIONS (complex combos) ────────────────────────────────
    add(Step.head("Happy", Category.EMOTION, dur = 2_500) {
        this[Idx.mouthSmileLeft] = 0.9f; this[Idx.mouthSmileRight] = 0.9f
        this[Idx.cheekSquintLeft] = 0.6f; this[Idx.cheekSquintRight] = 0.6f
        this[Idx.browOuterUpLeft] = 0.3f; this[Idx.browOuterUpRight] = 0.3f
        this[Idx.eyeSquintLeft] = 0.3f; this[Idx.eyeSquintRight] = 0.3f
    })
    add(Step.head("Sad", Category.EMOTION, dur = 2_500) {
        this[Idx.mouthFrownLeft] = 0.8f; this[Idx.mouthFrownRight] = 0.8f
        this[Idx.browInnerUp] = 0.9f; this[Idx.browDownLeft] = 0.3f; this[Idx.browDownRight] = 0.3f
        this[Idx.mouthPressLeft] = 0.3f; this[Idx.mouthPressRight] = 0.3f
    })
    add(Step.head("Angry", Category.EMOTION, dur = 2_500) {
        this[Idx.browDownLeft] = 1f; this[Idx.browDownRight] = 1f
        this[Idx.noseSneerLeft] = 0.6f; this[Idx.noseSneerRight] = 0.6f
        this[Idx.mouthStretchLeft] = 0.5f; this[Idx.mouthStretchRight] = 0.5f
        this[Idx.jawForward] = 0.3f
        this[Idx.eyeSquintLeft] = 0.4f; this[Idx.eyeSquintRight] = 0.4f
    })
    add(Step.head("Surprised", Category.EMOTION, dur = 2_500) {
        this[Idx.eyeWideLeft] = 0.9f; this[Idx.eyeWideRight] = 0.9f
        this[Idx.browOuterUpLeft] = 0.8f; this[Idx.browOuterUpRight] = 0.8f
        this[Idx.browInnerUp] = 0.8f
        this[Idx.jawOpen] = 0.6f
        this[Idx.mouthFunnel] = 0.3f
    })
    add(Step.head("Disgust", Category.EMOTION, dur = 2_500) {
        this[Idx.noseSneerLeft] = 1f; this[Idx.noseSneerRight] = 0.7f
        this[Idx.mouthUpperUpLeft] = 0.6f
        this[Idx.browDownLeft] = 0.5f; this[Idx.browDownRight] = 0.3f
        this[Idx.mouthFrownLeft] = 0.4f; this[Idx.mouthFrownRight] = 0.3f
        this[Idx.eyeSquintLeft] = 0.5f
    })
    add(Step.head("Fear", Category.EMOTION, dur = 2_500) {
        this[Idx.eyeWideLeft] = 1f; this[Idx.eyeWideRight] = 1f
        this[Idx.browInnerUp] = 1f
        this[Idx.browOuterUpLeft] = 0.5f; this[Idx.browOuterUpRight] = 0.5f
        this[Idx.jawOpen] = 0.3f
        this[Idx.mouthStretchLeft] = 0.6f; this[Idx.mouthStretchRight] = 0.6f
        this[Idx.mouthFrownLeft] = 0.3f; this[Idx.mouthFrownRight] = 0.3f
    })
    add(Step.head("Contempt", Category.EMOTION, dur = 2_500) {
        this[Idx.mouthSmileRight] = 0.5f
        this[Idx.mouthDimpleRight] = 0.4f
        this[Idx.browDownLeft] = 0.2f
        this[Idx.eyeSquintRight] = 0.2f
    })
    add(Step.head("Skeptical", Category.EMOTION, dur = 2_500) {
        this[Idx.browOuterUpLeft] = 0.8f
        this[Idx.browDownRight] = 0.4f
        this[Idx.mouthPucker] = 0.3f
        this[Idx.mouthRight] = 0.3f
    })

    // ── VISEMES (lip sync simulation) ────────────────────────────
    add(Step.head("Viseme: AA", Category.VISEME, dur = 1_200) {
        this[Idx.jawOpen] = 0.7f
        this[Idx.mouthLowerDownLeft] = 0.4f; this[Idx.mouthLowerDownRight] = 0.4f
    })
    add(Step.head("Viseme: EE", Category.VISEME, dur = 1_200) {
        this[Idx.mouthSmileLeft] = 0.6f; this[Idx.mouthSmileRight] = 0.6f
        this[Idx.mouthStretchLeft] = 0.3f; this[Idx.mouthStretchRight] = 0.3f
    })
    add(Step.head("Viseme: OO", Category.VISEME, dur = 1_200) {
        this[Idx.mouthFunnel] = 0.8f; this[Idx.mouthPucker] = 0.5f
        this[Idx.jawOpen] = 0.2f
    })
    add(Step.head("Viseme: FF/VV", Category.VISEME, dur = 1_200) {
        this[Idx.mouthRollLower] = 0.6f
        this[Idx.mouthUpperUpLeft] = 0.2f; this[Idx.mouthUpperUpRight] = 0.2f
    })
    add(Step.head("Viseme: TH", Category.VISEME, dur = 1_200) {
        this[Idx.jawOpen] = 0.15f
        this[Idx.mouthLowerDownLeft] = 0.5f; this[Idx.mouthLowerDownRight] = 0.5f
        this[Idx.mouthShrugUpper] = 0.3f
    })
    add(Step.head("Viseme: PP/BB/MM", Category.VISEME, dur = 1_200) {
        this[Idx.mouthClose] = 0.8f
        this[Idx.mouthPressLeft] = 0.5f; this[Idx.mouthPressRight] = 0.5f
    })

    // ── COMBINATION STRESS TESTS ─────────────────────────────────
    add(Step.head("Full Smile + Blink", Category.COMBO, dur = 2_000) {
        this[Idx.mouthSmileLeft] = 1f; this[Idx.mouthSmileRight] = 1f
        this[Idx.cheekSquintLeft] = 0.7f; this[Idx.cheekSquintRight] = 0.7f
        this[Idx.eyeBlinkLeft] = 0.8f; this[Idx.eyeBlinkRight] = 0.8f
    })
    add(Step.head("Talking + Looking", Category.COMBO, dur = 2_000) {
        this[Idx.jawOpen] = 0.5f
        this[Idx.mouthSmileLeft] = 0.3f; this[Idx.mouthSmileRight] = 0.3f
        this[Idx.eyeLookOutLeft] = 0.8f; this[Idx.eyeLookInRight] = 0.8f
        this[Idx.browOuterUpLeft] = 0.4f
    })
    add(Step.head("All Brows Max", Category.COMBO, dur = 2_000) {
        this[Idx.browDownLeft] = 1f; this[Idx.browDownRight] = 1f
        this[Idx.browInnerUp] = 1f
        this[Idx.browOuterUpLeft] = 1f; this[Idx.browOuterUpRight] = 1f
    })

    // ── STRESS: Rapid morph transitions ──────────────────────────
    add(Step.head("Stress: All Eyes", Category.STRESS, dur = 1_500) {
        this[Idx.eyeBlinkLeft] = 0.5f; this[Idx.eyeBlinkRight] = 0.5f
        this[Idx.eyeSquintLeft] = 0.5f; this[Idx.eyeSquintRight] = 0.5f
        this[Idx.eyeWideLeft] = 0.5f; this[Idx.eyeWideRight] = 0.5f
        this[Idx.eyeLookUpLeft] = 0.5f; this[Idx.eyeLookUpRight] = 0.5f
    })
    add(Step.head("Stress: All Mouth", Category.STRESS, dur = 1_500) {
        this[Idx.mouthSmileLeft] = 0.4f; this[Idx.mouthSmileRight] = 0.4f
        this[Idx.mouthFrownLeft] = 0.4f; this[Idx.mouthFrownRight] = 0.4f
        this[Idx.mouthPucker] = 0.5f; this[Idx.mouthFunnel] = 0.5f
        this[Idx.mouthRollLower] = 0.5f; this[Idx.mouthRollUpper] = 0.5f
        this[Idx.jawOpen] = 0.3f
    })
    add(Step.head("Stress: Max All 51", Category.STRESS, dur = 2_500) {
        for (i in 0 until 51) this[i] = 1f
    })
    add(Step.head("Stress: Half All 51", Category.STRESS, dur = 2_000) {
        for (i in 0 until 51) this[i] = 0.5f
    })

    // ── BUILT-IN GLB ANIMATION ───────────────────────────────────
    add(Step.anim("Built-in Anim (Scene)"))
}

// ══════════════════════════════════════════════════════════════════════════════
//  MORPH APPLICATION — maps 51 head weights to per-mesh morph targets
//
//  GLB mesh order (renderable entities after filtering):
//    0 = head_lod0_ORIGINAL  (51 targets)
//    1 = eyeLeft_ORIGINAL    (4 targets)
//    2 = eyeRight_ORIGINAL   (4 targets)
//    3 = teeth_ORIGINAL      (5 targets)
// ══════════════════════════════════════════════════════════════════════════════

private fun ModelNode.applyMorphs(headW: FloatArray) {
    val instance = modelInstance ?: return
    val rm       = engine.renderableManager

    // Подготавливаем суб-массивы для подмешей
    val teethW = floatArrayOf(
        headW[Idx.jawForward],
        headW[Idx.jawLeft],
        headW[Idx.jawRight],
        headW[Idx.jawOpen],
        headW[Idx.mouthClose],
    )
    val eyeLW = floatArrayOf(
        headW[Idx.eyeLookDownLeft],
        headW[Idx.eyeLookInLeft],
        headW[Idx.eyeLookOutLeft],
        headW[Idx.eyeLookUpLeft],
    )
    val eyeRW = floatArrayOf(
        headW[Idx.eyeLookDownRight],
        headW[Idx.eyeLookInRight],
        headW[Idx.eyeLookOutRight],
        headW[Idx.eyeLookUpRight],
    )

    // Получаем только mesh-entity (у которых есть RenderableComponent)
    val renderables = instance.entities
        .filter { rm.hasComponent(it) }
        .map { rm.getInstance(it) }

    renderables.forEachIndexed { i, ri ->
        val count = rm.getMorphTargetCount(ri)
        if (count <= 0) return@forEachIndexed

        val w = when (count) {
            51 -> headW                // head_lod0_ORIGINAL
            4  -> if (i <= 1) eyeLW    // eyeLeft — первый 4-target renderable
                  else eyeRW           // eyeRight — второй 4-target renderable
            5  -> teethW               // teeth_ORIGINAL
            else -> return@forEachIndexed
        }

        try {
            rm.setMorphWeights(ri, w, 0)
        } catch (_: Exception) {
            // Безопасно подавляем: несовпадение размеров на редких конфигурациях
        }
    }
}

private suspend fun ModelNode.animateMorphsSmooth(
    currentW: FloatArray,
    targetW: FloatArray,
    durationMs: Long
) {
    val startT = System.currentTimeMillis()
    val tmpArray = FloatArray(51)

    while (true) {
        val elapsed = System.currentTimeMillis() - startT
        val fraction = (elapsed.toFloat() / durationMs).coerceAtMost(1f)

        // EaseInOutQuad
        val easeT = if (fraction < 0.5f) {
            2f * fraction * fraction
        } else {
            1f - (-2f * fraction + 2f).let { it * it } / 2f
        }

        for (i in 0 until 51) {
            tmpArray[i] = currentW[i] + (targetW[i] - currentW[i]) * easeT
        }
        applyMorphs(tmpArray)

        if (fraction >= 1f) {
            targetW.copyInto(currentW)
            break
        }

        delay(16) // ~60 FPS, suspend point для отмены корутины
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
    var statusText  by remember { mutableStateOf("Loading model…") }

    val currentMorphState = remember { FloatArray(51) }
    var node by remember { mutableStateOf<ModelNode?>(null) }

    val scope = rememberCoroutineScope()

    val currentStep  = SEQUENCE.getOrNull(stepIndex)
    val totalSteps   = SEQUENCE.size
    val animProgress by animateFloatAsState(
        targetValue   = (stepIndex + 1).toFloat() / totalSteps,
        animationSpec = tween(600),
        label         = "progress",
    )

    // Timer
    LaunchedEffect(isFinished) {
        while (!isFinished) { delay(1_000); elapsedSec++ }
    }

    // Test sequence loop
    LaunchedEffect(node) {
        val n = node ?: return@LaunchedEffect
        statusText = "Running…"

        SEQUENCE.forEachIndexed { i, step ->
            stepIndex   = i
            isResetting = false

            if (step.headWeights == null) {
                // Built-in GLB animation
                statusText = "Playing GLB animation…"
                n.animateMorphsSmooth(currentMorphState, FloatArray(51), 300)
                n.playAnimation(0)
                delay(step.durationMs)
                n.stopAnimation(0)
            } else {
                statusText = step.label
                // Morph transition → hold → reset
                n.animateMorphsSmooth(currentMorphState, step.headWeights, 450)
                delay((step.durationMs - 450).coerceAtLeast(200))
                isResetting = true
                n.animateMorphsSmooth(currentMorphState, FloatArray(51), 400)
                delay(100)
            }
        }
        statusText = "Complete"
        isFinished = true
    }

    DisposableEffect(Unit) {
        onDispose {
            node?.stopAnimation(0)
        }
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { padding ->

        Column(
            modifier            = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // ── 3D Viewport ───────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0D0D0D))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory  = { ctx ->
                        SceneView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))

                            scope.launch {
                                delay(300) // Даём UI стабилизироваться

                                try {
                                    val modelInstance = modelLoader.createModelInstance(
                                        assetFileLocation = "models/source_named.glb"
                                    )

                                    if (modelInstance != null) {
                                        val modelNode = ModelNode(
                                            modelInstance = modelInstance,
                                            scaleToUnits  = 0.35f,
                                            // ═══════════════════════════════════════════
                                            // FIX: centerOrigin смещает bounding box
                                            // модели к (0,0,0) в локальных координатах.
                                            // Без этого геометрия головы сидит на Z≈1.56
                                            // и после позиционирования оказывается
                                            // ЗА камерой (камера смотрит вдоль −Z).
                                            // ═══════════════════════════════════════════
                                            centerOrigin  = Position(0f, 0f, 0f),
                                        )

                                        // Помещаем модель перед камерой:
                                        // Y слегка вниз чтобы голова была по центру вьюпорта
                                        modelNode.position = Position(x = 0f, y = -0.05f, z = -0.8f)

                                        addChildNode(modelNode)
                                        node = modelNode
                                    } else {
                                        statusText = "ERROR: model returned null"
                                    }
                                } catch (e: Exception) {
                                    statusText = "ERROR: ${e.message}"
                                    e.printStackTrace()
                                }
                            }
                        }
                    },
                    onRelease = { view ->
                        view.destroy()
                        node = null
                    },
                )

                if (!isFinished) ScanlineOverlay()
            }

            // ── HUD ───────────────────────────────────────────────
            Surface(
                modifier       = Modifier.fillMaxWidth(),
                color          = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier            = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {

                    LinearProgressIndicator(
                        progress      = { animProgress },
                        modifier      = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        strokeCap     = StrokeCap.Round,
                        color         = currentStep?.category?.color ?: MaterialTheme.colorScheme.primary,
                        trackColor    = MaterialTheme.colorScheme.surfaceVariant,
                    )

                    if (!isFinished && currentStep != null) {
                        AnimatedContent(
                            targetState   = stepIndex,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label         = "step_label",
                        ) { idx ->
                            val step = SEQUENCE.getOrNull(idx) ?: return@AnimatedContent
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CategoryChip(step.category)
                                Text(
                                    text       = if (isResetting) "↩ Reset…" else step.label,
                                    style      = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        if (currentStep.headWeights != null && !isResetting) {
                            ActiveWeightsRow(currentStep.headWeights)
                        }
                    }

                    if (isFinished) {
                        Text(
                            text       = "All $totalSteps steps complete",
                            fontWeight = FontWeight.Bold,
                            color      = Color(0xFF2E7D32),
                        )
                    }

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text  = "Step ${(stepIndex + 1).coerceAtMost(totalSteps)} / $totalSteps",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text  = "${elapsedSec}s",
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
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = category.color.copy(alpha = 0.15f),
    ) {
        Text(
            text       = category.label,
            color      = category.color,
            fontSize   = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ActiveWeightsRow(weights: FloatArray) {
    val active = mutableListOf<Pair<Int, Float>>()
    for (i in weights.indices) {
        if (weights[i] > 0.001f) active.add(i to weights[i])
    }
    if (active.isEmpty()) return

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((idx, value) in active) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text     = "#$idx=${"%.2f".format(value)}",
                    fontSize = 10.sp,
                    color    = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ScanlineOverlay() {
    val inf   = rememberInfiniteTransition(label = "scan")
    val alpha by inf.animateFloat(
        initialValue  = 0f,
        targetValue   = 0.06f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label         = "scan_alpha",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF00E5FF).copy(alpha = alpha))
    )
}
