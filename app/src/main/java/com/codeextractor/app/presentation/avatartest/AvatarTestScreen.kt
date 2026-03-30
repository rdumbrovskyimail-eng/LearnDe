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
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Engine
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ══════════════════════════════════════════════════════════════════════════════
//  MODEL CONSTANTS — verified against source_named.glb binary
//
//  Entity order inside ModelInstance (matches GLB mesh array order):
//    entities[0] = teeth_ORIGINAL    →  5 morph targets
//    entities[1] = head_lod0_ORIGINAL → 51 morph targets
//    entities[2] = eyeLeft_ORIGINAL  →  4 morph targets
//    entities[3] = eyeRight_ORIGINAL →  4 morph targets
// ══════════════════════════════════════════════════════════════════════════════

private object Idx {
    // ── head_lod0_ORIGINAL (51 targets) ──────────────────────────────────────
    const val eyeBlinkLeft       = 0;  const val eyeLookDownLeft    = 1
    const val eyeLookInLeft      = 2;  const val eyeLookOutLeft     = 3
    const val eyeLookUpLeft      = 4;  const val eyeSquintLeft      = 5
    const val eyeWideLeft        = 6;  const val eyeBlinkRight      = 7
    const val eyeLookDownRight   = 8;  const val eyeLookInRight     = 9
    const val eyeLookOutRight    = 10; const val eyeLookUpRight     = 11
    const val eyeSquintRight     = 12; const val eyeWideRight       = 13
    const val jawForward         = 14; const val jawLeft            = 15
    const val jawRight           = 16; const val jawOpen            = 17
    const val mouthClose         = 18; const val mouthFunnel        = 19
    const val mouthPucker        = 20; const val mouthRight         = 21
    const val mouthLeft          = 22; const val mouthSmileLeft     = 23
    const val mouthSmileRight    = 24; const val mouthFrownLeft     = 25
    const val mouthFrownRight    = 26; const val mouthDimpleLeft    = 27
    const val mouthDimpleRight   = 28; const val mouthStretchLeft   = 29
    const val mouthStretchRight  = 30; const val mouthRollLower     = 31
    const val mouthRollUpper     = 32; const val mouthShrugLower    = 33
    const val mouthShrugUpper    = 34; const val mouthPressLeft     = 35
    const val mouthPressRight    = 36; const val mouthLowerDownLeft = 37
    const val mouthLowerDownRight= 38; const val mouthUpperUpLeft   = 39
    const val mouthUpperUpRight  = 40; const val browDownLeft       = 41
    const val browDownRight      = 42; const val browInnerUp        = 43
    const val browOuterUpLeft    = 44; const val browOuterUpRight   = 45
    const val cheekPuff          = 46; const val cheekSquintLeft    = 47
    const val cheekSquintRight   = 48; const val noseSneerLeft      = 49
    const val noseSneerRight     = 50

    // ── teeth_ORIGINAL (5 targets, entity[0]) ────────────────────────────────
    val TEETH_JAW_FORWARD = 0; val TEETH_JAW_LEFT  = 1; val TEETH_JAW_RIGHT = 2
    val TEETH_JAW_OPEN    = 3; val TEETH_MOUTH_CLOSE = 4

    // ── eyeLeft_ORIGINAL (4 targets, entity[2]) ──────────────────────────────
    val EL_DOWN = 0; val EL_IN = 1; val EL_OUT = 2; val EL_UP = 3

    // ── eyeRight_ORIGINAL (4 targets, entity[3]) ─────────────────────────────
    val ER_DOWN = 0; val ER_IN = 1; val ER_OUT = 2; val ER_UP = 3
}

// ══════════════════════════════════════════════════════════════════════════════
//  TEST SEQUENCE
// ══════════════════════════════════════════════════════════════════════════════

private enum class Category(val label: String, val color: Color) {
    EYES     ("Eyes",      Color(0xFF1565C0)),
    BROWS    ("Brows",     Color(0xFF6A1B9A)),
    JAW      ("Jaw",       Color(0xFF00695C)),
    MOUTH    ("Mouth",     Color(0xFF2E7D32)),
    FACE     ("Face",      Color(0xFFE65100)),
    EMOTION  ("Emotion",   Color(0xFFC62828)),
    ANIMATION("Animation", Color(0xFF37474F)),
}

private data class Step(
    val label      : String,
    val category   : Category,
    // head weights array (51 floats) — null means "play built-in animation"
    val headWeights: FloatArray? = FloatArray(51),
    val durationMs : Long        = 1_800,
) {
    companion object {
        fun head(label: String, cat: Category, dur: Long = 1_800, block: FloatArray.() -> Unit): Step {
            val w = FloatArray(51)
            w.block()
            return Step(label, cat, w, dur)
        }
        fun animation(label: String, dur: Long): Step =
            Step(label, Category.ANIMATION, headWeights = null, durationMs = dur)
    }
}

private val SEQUENCE: List<Step> = buildList {

    // ── EYES ─────────────────────────────────────────────────────────────────
    add(Step.head("Blink Left",    Category.EYES) { set(Idx.eyeBlinkLeft, 1f) })
    add(Step.head("Blink Right",   Category.EYES) { set(Idx.eyeBlinkRight, 1f) })
    add(Step.head("Blink Both",    Category.EYES) { set(Idx.eyeBlinkLeft, 1f); set(Idx.eyeBlinkRight, 1f) })
    add(Step.head("Look Up",       Category.EYES) { set(Idx.eyeLookUpLeft, 1f); set(Idx.eyeLookUpRight, 1f) })
    add(Step.head("Look Down",     Category.EYES) { set(Idx.eyeLookDownLeft, 1f); set(Idx.eyeLookDownRight, 1f) })
    add(Step.head("Look Left",     Category.EYES) { set(Idx.eyeLookOutLeft, 1f); set(Idx.eyeLookInRight, 1f) })
    add(Step.head("Look Right",    Category.EYES) { set(Idx.eyeLookInLeft, 1f); set(Idx.eyeLookOutRight, 1f) })
    add(Step.head("Eye Squint",    Category.EYES) { set(Idx.eyeSquintLeft, 1f); set(Idx.eyeSquintRight, 1f) })
    add(Step.head("Eye Wide",      Category.EYES) { set(Idx.eyeWideLeft, 1f); set(Idx.eyeWideRight, 1f) })

    // ── BROWS ────────────────────────────────────────────────────────────────
    add(Step.head("Brow Down",      Category.BROWS) { set(Idx.browDownLeft, 1f); set(Idx.browDownRight, 1f) })
    add(Step.head("Brow Inner Up",  Category.BROWS) { set(Idx.browInnerUp, 1f) })
    add(Step.head("Brow Outer Up",  Category.BROWS) { set(Idx.browOuterUpLeft, 1f); set(Idx.browOuterUpRight, 1f) })

    // ── JAW ──────────────────────────────────────────────────────────────────
    add(Step.head("Jaw Open",    Category.JAW) { set(Idx.jawOpen, 1f) })
    add(Step.head("Jaw Forward", Category.JAW) { set(Idx.jawForward, 1f) })
    add(Step.head("Jaw Left",    Category.JAW) { set(Idx.jawLeft, 1f) })
    add(Step.head("Jaw Right",   Category.JAW) { set(Idx.jawRight, 1f) })

    // ── MOUTH ────────────────────────────────────────────────────────────────
    add(Step.head("Smile",        Category.MOUTH) { set(Idx.mouthSmileLeft, 1f); set(Idx.mouthSmileRight, 1f) })
    add(Step.head("Frown",        Category.MOUTH) { set(Idx.mouthFrownLeft, 1f); set(Idx.mouthFrownRight, 1f) })
    add(Step.head("Pucker",       Category.MOUTH) { set(Idx.mouthPucker, 1f) })
    add(Step.head("Funnel",       Category.MOUTH) { set(Idx.mouthFunnel, 1f) })
    add(Step.head("Mouth Right",  Category.MOUTH) { set(Idx.mouthRight, 1f) })
    add(Step.head("Mouth Left",   Category.MOUTH) { set(Idx.mouthLeft, 1f) })
    add(Step.head("Dimples",      Category.MOUTH) { set(Idx.mouthDimpleLeft, 1f); set(Idx.mouthDimpleRight, 1f) })
    add(Step.head("Stretch",      Category.MOUTH) { set(Idx.mouthStretchLeft, 1f); set(Idx.mouthStretchRight, 1f) })
    add(Step.head("Roll Lower",   Category.MOUTH) { set(Idx.mouthRollLower, 1f) })
    add(Step.head("Roll Upper",   Category.MOUTH) { set(Idx.mouthRollUpper, 1f) })
    add(Step.head("Shrug Lower",  Category.MOUTH) { set(Idx.mouthShrugLower, 1f) })
    add(Step.head("Shrug Upper",  Category.MOUTH) { set(Idx.mouthShrugUpper, 1f) })
    add(Step.head("Press",        Category.MOUTH) { set(Idx.mouthPressLeft, 1f); set(Idx.mouthPressRight, 1f) })
    add(Step.head("Lower Down",   Category.MOUTH) { set(Idx.mouthLowerDownLeft, 1f); set(Idx.mouthLowerDownRight, 1f) })
    add(Step.head("Upper Up",     Category.MOUTH) { set(Idx.mouthUpperUpLeft, 1f); set(Idx.mouthUpperUpRight, 1f) })
    add(Step.head("Close",        Category.MOUTH) { set(Idx.mouthClose, 1f) })

    // ── FACE ─────────────────────────────────────────────────────────────────
    add(Step.head("Cheek Puff",   Category.FACE) { set(Idx.cheekPuff, 1f) })
    add(Step.head("Cheek Squint", Category.FACE) { set(Idx.cheekSquintLeft, 1f); set(Idx.cheekSquintRight, 1f) })
    add(Step.head("Nose Sneer",   Category.FACE) { set(Idx.noseSneerLeft, 1f); set(Idx.noseSneerRight, 1f) })

    // ── COMPOSITE EMOTIONS ───────────────────────────────────────────────────
    add(Step.head("Happy 😊", Category.EMOTION, dur = 2_500) {
        set(Idx.mouthSmileLeft, 0.9f); set(Idx.mouthSmileRight, 0.9f)
        set(Idx.cheekSquintLeft, 0.5f); set(Idx.cheekSquintRight, 0.5f)
        set(Idx.browOuterUpLeft, 0.3f); set(Idx.browOuterUpRight, 0.3f)
    })
    add(Step.head("Sad 😢", Category.EMOTION, dur = 2_500) {
        set(Idx.mouthFrownLeft, 0.8f); set(Idx.mouthFrownRight, 0.8f)
        set(Idx.browInnerUp, 0.7f)
        set(Idx.eyeSquintLeft, 0.3f); set(Idx.eyeSquintRight, 0.3f)
    })
    add(Step.head("Angry 😠", Category.EMOTION, dur = 2_500) {
        set(Idx.browDownLeft, 1f); set(Idx.browDownRight, 1f)
        set(Idx.noseSneerLeft, 0.5f); set(Idx.noseSneerRight, 0.5f)
        set(Idx.mouthStretchLeft, 0.4f); set(Idx.mouthStretchRight, 0.4f)
        set(Idx.jawForward, 0.3f)
    })
    add(Step.head("Surprised 😲", Category.EMOTION, dur = 2_500) {
        set(Idx.jawOpen, 0.8f)
        set(Idx.eyeWideLeft, 1f); set(Idx.eyeWideRight, 1f)
        set(Idx.browOuterUpLeft, 1f); set(Idx.browOuterUpRight, 1f)
        set(Idx.browInnerUp, 0.8f)
    })
    add(Step.head("Disgust 🤢", Category.EMOTION, dur = 2_500) {
        set(Idx.noseSneerLeft, 0.9f); set(Idx.noseSneerRight, 0.9f)
        set(Idx.mouthUpperUpLeft, 0.6f); set(Idx.mouthUpperUpRight, 0.4f)
        set(Idx.browDownLeft, 0.5f); set(Idx.eyeSquintLeft, 0.4f)
    })
    add(Step.head("Fear 😨", Category.EMOTION, dur = 2_500) {
        set(Idx.eyeWideLeft, 0.8f); set(Idx.eyeWideRight, 0.8f)
        set(Idx.browInnerUp, 1f)
        set(Idx.browOuterUpLeft, 0.6f); set(Idx.browOuterUpRight, 0.6f)
        set(Idx.jawOpen, 0.4f)
        set(Idx.mouthStretchLeft, 0.3f); set(Idx.mouthStretchRight, 0.3f)
    })
    add(Step.head("Contempt 😏", Category.EMOTION, dur = 2_000) {
        set(Idx.mouthSmileLeft, 0.7f)
        set(Idx.mouthDimpleLeft, 0.4f)
        set(Idx.browDownRight, 0.3f)
        set(Idx.eyeSquintLeft, 0.2f)
    })
    add(Step.head("Wink 😉", Category.EMOTION, dur = 2_000) {
        set(Idx.eyeBlinkLeft, 1f)
        set(Idx.mouthSmileRight, 0.6f)
        set(Idx.cheekSquintRight, 0.3f)
    })

    // ── BUILT-IN ANIMATION ───────────────────────────────────────────────────
    add(Step.animation("Built-in Anim 🎬", dur = 20_500))
}

// ══════════════════════════════════════════════════════════════════════════════
//  MORPH APPLICATION
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Apply head morph weights to all 4 mesh entities.
 * Entity index inside ModelInstance mirrors GLB mesh order:
 *   0 = teeth (5 targets), 1 = head (51), 2 = eyeL (4), 3 = eyeR (4)
 */
private fun ModelNode.applyMorphs(headW: FloatArray) {
    val instance = modelInstance ?: return
    val rm       = instance.engine.renderableManager
    val entities = instance.entities

    // teeth — replicate relevant jaw/mouth weights
    val teethW = FloatArray(5).apply {
        this[Idx.TEETH_JAW_FORWARD]   = headW[Idx.jawForward]
        this[Idx.TEETH_JAW_LEFT]      = headW[Idx.jawLeft]
        this[Idx.TEETH_JAW_RIGHT]     = headW[Idx.jawRight]
        this[Idx.TEETH_JAW_OPEN]      = headW[Idx.jawOpen]
        this[Idx.TEETH_MOUTH_CLOSE]   = headW[Idx.mouthClose]
    }
    // eye left — replicate look weights
    val eyeLW = FloatArray(4).apply {
        this[Idx.EL_DOWN] = headW[Idx.eyeLookDownLeft]
        this[Idx.EL_IN]   = headW[Idx.eyeLookInLeft]
        this[Idx.EL_OUT]  = headW[Idx.eyeLookOutLeft]
        this[Idx.EL_UP]   = headW[Idx.eyeLookUpLeft]
    }
    // eye right
    val eyeRW = FloatArray(4).apply {
        this[Idx.ER_DOWN] = headW[Idx.eyeLookDownRight]
        this[Idx.ER_IN]   = headW[Idx.eyeLookInRight]
        this[Idx.ER_OUT]  = headW[Idx.eyeLookOutRight]
        this[Idx.ER_UP]   = headW[Idx.eyeLookUpRight]
    }

    entities.forEachIndexed { i, entity ->
        if (!rm.hasComponent(entity)) return@forEachIndexed
        val ri = rm.getInstance(entity)
        val w  = when (i) {
            0 -> teethW
            1 -> headW
            2 -> eyeLW
            3 -> eyeRW
            else -> return@forEachIndexed
        }
        rm.setMorphWeights(ri, w, 0)
    }
}

private fun ModelNode.clearMorphs() = applyMorphs(FloatArray(51))

// ══════════════════════════════════════════════════════════════════════════════
//  SCREEN
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarTestScreen(onBack: () -> Unit) {

    // ── State ─────────────────────────────────────────────────────────────────
    var stepIndex   by remember { mutableIntStateOf(0) }
    var isResetting by remember { mutableStateOf(false) }
    var elapsedSec  by remember { mutableIntStateOf(0) }
    var isFinished  by remember { mutableStateOf(false) }
    var node        by remember { mutableStateOf<ModelNode?>(null) }

    val currentStep  = SEQUENCE.getOrNull(stepIndex)
    val totalSteps   = SEQUENCE.size
    val animProgress by animateFloatAsState(
        targetValue   = stepIndex.toFloat() / totalSteps,
        animationSpec = tween(600),
        label         = "progress",
    )

    // ── Timer ─────────────────────────────────────────────────────────────────
    LaunchedEffect(isFinished) {
        while (isActive && !isFinished) { delay(1_000); elapsedSec++ }
    }

    // ── Test loop ─────────────────────────────────────────────────────────────
    LaunchedEffect(node) {
        val n = node ?: return@LaunchedEffect

        SEQUENCE.forEachIndexed { i, step ->
            stepIndex   = i
            isResetting = false

            if (step.headWeights == null) {
                // Built-in animation
                n.playAnimation(0)
                delay(step.durationMs)
                n.stopAnimation(0)
                n.clearMorphs()
            } else {
                n.applyMorphs(step.headWeights)
                delay(step.durationMs)
                isResetting = true
                n.clearMorphs()
                delay(250)
            }
        }

        isFinished = true
    }

    // ── Cleanup on leave ──────────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose { node?.stopAnimation(0); node?.clearMorphs() }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
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

            // ── 3D Viewport ───────────────────────────────────────────────────
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
                            // Neutral grey environment — model читается чётко
                            setBackgroundColor(0.05f, 0.05f, 0.05f, 1f)

                            // Camera — чуть выше центра, небольшой отступ
                            camera.position = Position(x = 0f, y = 0.07f, z = 0.32f)
                            camera.lookAt(Position(x = 0f, y = 0.07f, z = 0f))

                            val modelInstance = modelLoader.createModelInstance(
                                assetFileLocation = "models/source_named.glb"
                            )
                            val modelNode = ModelNode(
                                modelInstance = modelInstance,
                                scaleToUnits  = 0.20f,
                            ).apply {
                                position = Position(x = 0f, y = -0.09f, z = 0f)
                            }
                            addChildNode(modelNode)
                            node = modelNode
                        }
                    },
                )

                // Scanning overlay — показывает что идёт активный тест
                if (!isFinished) ScanlineOverlay()
            }

            // ── HUD ───────────────────────────────────────────────────────────
            Surface(
                modifier  = Modifier.fillMaxWidth(),
                color     = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier            = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {

                    // Progress bar
                    LinearProgressIndicator(
                        progress      = { animProgress },
                        modifier      = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        strokeCap     = StrokeCap.Round,
                        color         = currentStep?.category?.color ?: MaterialTheme.colorScheme.primary,
                        trackColor    = MaterialTheme.colorScheme.surfaceVariant,
                    )

                    // Step label
                    if (!isFinished && currentStep != null) {
                        AnimatedContent(
                            targetState   = currentStep.label,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label         = "step_label",
                        ) { label ->
                            Row(
                                verticalAlignment    = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CategoryChip(currentStep.category)
                                Text(
                                    text       = if (isResetting) "↩ Reset…" else label,
                                    style      = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        // Active morph weights — only when not an animation step
                        if (currentStep.headWeights != null && !isResetting) {
                            ActiveWeightsRow(currentStep.headWeights)
                        }
                    }

                    if (isFinished) {
                        Row(
                            verticalAlignment    = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint    = Color(0xFF2E7D32),
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text       = "All $totalSteps steps complete",
                                fontWeight = FontWeight.Bold,
                                color      = Color(0xFF2E7D32),
                            )
                        }
                    }

                    // Stats
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
                            text  = "⏱ ${elapsedSec}s",
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
            text     = category.label,
            color    = category.color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ActiveWeightsRow(weights: FloatArray) {
    // Show only non-zero weights, max 6
    val active = weights.mapIndexedNotNull { i, v -> if (v > 0.001f) i to v else null }.take(6)
    if (active.isEmpty()) return

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        active.forEach { (idx, value) ->
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

/** Subtle animated scan lines to indicate active testing */
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
