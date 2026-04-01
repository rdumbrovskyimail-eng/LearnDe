package com.codeextractor.app.presentation.avatartest

import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.Scene
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

// ─────────────────────────────────────────────────────────────────────────────
private const val TAG        = "AvatarTest"
private const val MODEL_PATH = "models/source_named.glb"

// ─────────────────────────────────────────────────────────────────────────────
//  DIAGNOSTIC LOGGER
// ─────────────────────────────────────────────────────────────────────────────

private object DiagLog {
    private val lines     = CopyOnWriteArrayList<String>()
    private val startTime = System.currentTimeMillis()

    private fun log(level: String, msg: String) {
        val ts = System.currentTimeMillis() - startTime
        lines.add("[${ts}ms][$level] $msg")
        when (level) {
            "E" -> Log.e(TAG, msg)
            "W" -> Log.w(TAG, msg)
            "I" -> Log.i(TAG, msg)
            else -> Log.d(TAG, msg)
        }
    }

    fun d(msg: String) = log("D", msg)
    fun i(msg: String) = log("I", msg)
    fun w(msg: String) = log("W", msg)
    fun e(msg: String) = log("E", msg)

    fun getFullLog(): String = buildString {
        appendLine("══════════════════════════════════════════")
        appendLine("  AVATAR TEST DIAGNOSTIC LOG")
        appendLine("  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("  Entries: ${lines.size}")
        appendLine("══════════════════════════════════════════")
        appendLine()
        lines.forEach { appendLine(it) }
    }

    fun clear() = lines.clear()
}

// ─────────────────────────────────────────────────────────────────────────────
//  ASSET VALIDATOR
// ─────────────────────────────────────────────────────────────────────────────

private fun validateGlbAsset(context: Context, path: String): String {
    return try {
        val stream = context.assets.open(path)
        val header = ByteArray(12)
        val read   = stream.read(header)
        var total  = read.toLong()
        val buf    = ByteArray(8192)
        while (true) { val n = stream.read(buf); if (n <= 0) break; total += n }
        stream.close()
        val isGlb = read >= 4 &&
            header[0] == 0x67.toByte() && header[1] == 0x6C.toByte() &&
            header[2] == 0x54.toByte() && header[3] == 0x46.toByte()
        DiagLog.i("GLB valid=$isGlb  size=${total / 1024}KB")
        if (isGlb) "OK ${total / 1024}KB" else "FAIL: not GLB"
    } catch (e: Exception) {
        DiagLog.e("validateGlb: ${e.message}")
        "FAIL: ${e.message}"
    }
}

private fun listAssets(context: Context) {
    try {
        val root = context.assets.list("") ?: emptyArray()
        DiagLog.d("assets root: ${root.joinToString()}")
        if ("models" in root) {
            DiagLog.d("models/: ${context.assets.list("models")?.joinToString()}")
        } else {
            DiagLog.e("'models' dir NOT found in assets!")
        }
    } catch (e: Exception) { DiagLog.e("listAssets: ${e.message}") }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ARKit 52 morph-target indices   (head_lod0_ORIGINAL mesh)
// ─────────────────────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────────────────────
//  TEST SEQUENCE
// ─────────────────────────────────────────────────────────────────────────────

private enum class Category(val label: String, val color: Color) {
    EYES("Eyes",       Color(0xFF1565C0)),
    BROWS("Brows",     Color(0xFF6A1B9A)),
    JAW("Jaw",         Color(0xFF00695C)),
    MOUTH("Mouth",     Color(0xFF2E7D32)),
    CHEEKS("Cheeks",   Color(0xFF795548)),
    NOSE("Nose",       Color(0xFF607D8B)),
    EMOTION("Emotion", Color(0xFFC62828)),
    VISEME("Viseme",   Color(0xFF0097A7)),
    ANIMATION("Anim",  Color(0xFF37474F)),
}

private data class Step(
    val label: String,
    val category: Category,
    val headWeights: FloatArray? = FloatArray(51),
    val durationMs: Long = 1_800,
) {
    companion object {
        fun head(l: String, c: Category, dur: Long = 1_800, b: FloatArray.() -> Unit): Step {
            val w = FloatArray(51); w.b(); return Step(l, c, w, dur)
        }
        fun anim(l: String, dur: Long = 20_500) =
            Step(l, Category.ANIMATION, headWeights = null, durationMs = dur)
    }
}

private val SEQUENCE: List<Step> = buildList {
    add(Step.head("Blink Both",    Category.EYES)  { this[Idx.eyeBlinkLeft]=1f;  this[Idx.eyeBlinkRight]=1f })
    add(Step.head("Look Left",     Category.EYES)  { this[Idx.eyeLookOutLeft]=1f;this[Idx.eyeLookInRight]=1f })
    add(Step.head("Look Up",       Category.EYES)  { this[Idx.eyeLookUpLeft]=1f; this[Idx.eyeLookUpRight]=1f })
    add(Step.head("Wide Eyes",     Category.EYES)  { this[Idx.eyeWideLeft]=1f;   this[Idx.eyeWideRight]=1f })
    add(Step.head("Brow Down",     Category.BROWS) { this[Idx.browDownLeft]=1f;  this[Idx.browDownRight]=1f })
    add(Step.head("Brow Inner Up", Category.BROWS) { this[Idx.browInnerUp]=1f })
    add(Step.head("Jaw Open",      Category.JAW)   { this[Idx.jawOpen]=1f })
    add(Step.head("Jaw Forward",   Category.JAW)   { this[Idx.jawForward]=1f })
    add(Step.head("Smile",         Category.MOUTH) { this[Idx.mouthSmileLeft]=1f; this[Idx.mouthSmileRight]=1f })
    add(Step.head("Frown",         Category.MOUTH) { this[Idx.mouthFrownLeft]=1f; this[Idx.mouthFrownRight]=1f })
    add(Step.head("Pucker",        Category.MOUTH) { this[Idx.mouthPucker]=1f })
    add(Step.head("Funnel",        Category.MOUTH) { this[Idx.mouthFunnel]=1f })
    add(Step.head("Cheek Puff",    Category.CHEEKS){ this[Idx.cheekPuff]=1f })
    add(Step.head("Nose Sneer",    Category.NOSE)  { this[Idx.noseSneerLeft]=1f; this[Idx.noseSneerRight]=1f })
    add(Step.head("Happy", Category.EMOTION, 2500) {
        this[Idx.mouthSmileLeft]=0.9f; this[Idx.mouthSmileRight]=0.9f
        this[Idx.cheekSquintLeft]=0.6f; this[Idx.cheekSquintRight]=0.6f
    })
    add(Step.head("Angry", Category.EMOTION, 2500) {
        this[Idx.browDownLeft]=1f;    this[Idx.browDownRight]=1f
        this[Idx.noseSneerLeft]=0.6f; this[Idx.noseSneerRight]=0.6f
        this[Idx.jawForward]=0.3f
    })
    add(Step.head("Surprised", Category.EMOTION, 2500) {
        this[Idx.eyeWideLeft]=0.9f;  this[Idx.eyeWideRight]=0.9f
        this[Idx.browInnerUp]=0.8f;  this[Idx.jawOpen]=0.6f
    })
    add(Step.head("Viseme AA", Category.VISEME, 1200) { this[Idx.jawOpen]=0.7f })
    add(Step.head("Viseme OO", Category.VISEME, 1200) { this[Idx.mouthFunnel]=0.8f; this[Idx.mouthPucker]=0.5f })
    add(Step.anim("Built-in Anim"))
}

// ─────────────────────────────────────────────────────────────────────────────
//  MORPH APPLICATION
// ─────────────────────────────────────────────────────────────────────────────

private fun applyMorphs(engine: Engine, instance: ModelInstance, headW: FloatArray) {
    val rm     = engine.renderableManager
    val teethW = floatArrayOf(
        headW[Idx.jawForward], headW[Idx.jawLeft], headW[Idx.jawRight],
        headW[Idx.jawOpen],    headW[Idx.mouthClose]
    )
    val eyeLW = floatArrayOf(
        headW[Idx.eyeLookDownLeft], headW[Idx.eyeLookInLeft],
        headW[Idx.eyeLookOutLeft],  headW[Idx.eyeLookUpLeft]
    )
    val eyeRW = floatArrayOf(
        headW[Idx.eyeLookDownRight], headW[Idx.eyeLookInRight],
        headW[Idx.eyeLookOutRight],  headW[Idx.eyeLookUpRight]
    )
    var eye4 = 0
    instance.entities
        .filter { rm.hasComponent(it) }
        .map    { rm.getInstance(it) }
        .forEach { ri ->
            val count = rm.getMorphTargetCount(ri)
            if (count <= 0) return@forEach
            val w = when (count) {
                51 -> headW
                5  -> teethW
                4  -> if (eye4++ == 0) eyeLW else eyeRW
                else -> { DiagLog.w("unexpected morphCount=$count"); return@forEach }
            }
            try { rm.setMorphWeights(ri, w, 0) }
            catch (ex: Exception) { DiagLog.e("setMorphWeights count=$count: ${ex.message}") }
        }
}

private suspend fun animateSmooth(
    engine: Engine, instance: ModelInstance,
    cur: FloatArray, tgt: FloatArray, durMs: Long,
) {
    val start = System.currentTimeMillis()
    val tmp   = FloatArray(51)
    while (true) {
        val frac = ((System.currentTimeMillis() - start).toFloat() / durMs).coerceAtMost(1f)
        val t    = if (frac < 0.5f) 2f * frac * frac
                   else 1f - (-2f * frac + 2f).let { it * it } / 2f
        for (i in 0 until 51) tmp[i] = cur[i] + (tgt[i] - cur[i]) * t
        applyMorphs(engine, instance, tmp)
        if (frac >= 1f) { tgt.copyInto(cur); break }
        delay(16)
    }
}

private suspend fun playAnimation(instance: ModelInstance, maxMs: Long) {
    val animator = instance.animator
        ?: throw IllegalStateException("No animator on instance")
    if (animator.animationCount == 0)
        throw IllegalStateException("Model has 0 animations")
    val durMs = (animator.getAnimationDuration(0) * 1000).toLong()
    val limit  = minOf(maxMs, durMs)
    val start  = System.currentTimeMillis()
    DiagLog.i("playAnimation dur=${durMs}ms limit=${limit}ms")
    while (true) {
        val elapsed = System.currentTimeMillis() - start
        if (elapsed >= limit) break
        animator.applyAnimation(0, elapsed / 1000f)
        animator.updateBoneMatrices()
        delay(16)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarTestScreen(onBack: () -> Unit) {

    val context = LocalContext.current

    // ── UI state ──────────────────────────────────────────────────────────
    var stepIndex      by remember { mutableIntStateOf(0) }
    var isResetting    by remember { mutableStateOf(false) }
    var elapsedSec     by remember { mutableIntStateOf(0) }
    var isFinished     by remember { mutableStateOf(false) }
    var statusText     by remember { mutableStateOf("Initializing…") }
    var showSaveDialog by remember { mutableStateOf(false) }
    val morphState     = remember { FloatArray(51) }

    // ─────────────────────────────────────────────────────────────────────
    //  SceneView 3.x resources
    // ─────────────────────────────────────────────────────────────────────
    val engine            = rememberEngine()
    val modelLoader       = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val cameraNode = rememberCameraNode(engine) {
        position = Float3(x = 0f, y = -0.02f, z = 2.0f)
        lookAt(Float3(0f, -0.02f, 1.56f))
    }

    val environment = rememberEnvironment(engine)

    // Модель — null пока грузится, non-null когда готова
    val modelInstance = rememberModelInstance(modelLoader, MODEL_PATH)

    // ── One-shot diagnostics ──────────────────────────────────────────────
    LaunchedEffect(Unit) {
        DiagLog.clear()
        DiagLog.i("═══ AVATAR TEST SESSION ═══")
        DiagLog.d("SDK=${android.os.Build.VERSION.SDK_INT}  " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        listAssets(context)
        statusText = "Asset: ${validateGlbAsset(context, MODEL_PATH)}"
    }

    // ── Timer ─────────────────────────────────────────────────────────────
    LaunchedEffect(isFinished) {
        while (!isFinished) { delay(1_000); elapsedSec++ }
    }

    // ── Auto-show save dialog at 50 s ─────────────────────────────────────
    LaunchedEffect(Unit) { delay(50_000); showSaveDialog = true }

    // ── Test sequence ─────────────────────────────────────────────────────
    LaunchedEffect(modelInstance) {
        val inst = modelInstance ?: run {
            DiagLog.d("modelInstance null — waiting for load")
            return@LaunchedEffect
        }

        DiagLog.i("╔══ MODEL READY ══╗")
        DiagLog.d("  entities: ${inst.entities.size}")
        DiagLog.d("  animationCount: ${inst.animator?.animationCount}")
        val rm = engine.renderableManager
        inst.entities.forEachIndexed { i, e ->
            if (rm.hasComponent(e))
                DiagLog.d("  entity[$i] morphTargets=${rm.getMorphTargetCount(rm.getInstance(e))}")
        }

        statusText = "Model loaded! Starting test…"
        delay(600)

        SEQUENCE.forEachIndexed { i, step ->
            stepIndex = i; isResetting = false
            statusText = step.label
            DiagLog.d("Step[$i] ${step.category.label}: ${step.label}")

            if (step.headWeights == null) {
                animateSmooth(engine, inst, morphState, FloatArray(51), 300)
                try {
                    playAnimation(inst, step.durationMs)
                    statusText = "OK: anim played"
                    DiagLog.i("  → anim OK")
                } catch (ex: Exception) {
                    statusText = "SKIP: ${ex.message}"
                    DiagLog.w("  → anim skip: ${ex.message}")
                    delay(step.durationMs)
                }
            } else {
                animateSmooth(engine, inst, morphState, step.headWeights, 450)
                statusText = "OK: ${step.label}"
                delay((step.durationMs - 450).coerceAtLeast(200))
                isResetting = true
                animateSmooth(engine, inst, morphState, FloatArray(51), 400)
                delay(100)
            }
        }

        DiagLog.i("═══ SEQUENCE COMPLETE ═══")
        statusText  = "All ${SEQUENCE.size} tests complete"
        isFinished  = true
        showSaveDialog = true
    }

    // ── SAF launcher ──────────────────────────────────────────────────────
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(DiagLog.getFullLog().toByteArray())
            }
            statusText = "Log saved!"
        } catch (e: Exception) { DiagLog.e("save: ${e.message}") }
    }

    // ── Save dialog ───────────────────────────────────────────────────────
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Diagnostic Log") },
            text  = {
                Text(
                    "${DiagLog.getFullLog().lines().size} entries\n" +
                    "Model: ${if (modelInstance != null) "✓ LOADED" else "✖ NOT LOADED"}\n" +
                    "Elapsed: ${elapsedSec}s"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    saveLauncher.launch("avatar_test_${System.currentTimeMillis()}.txt")
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Later") }
            }
        )
    }

    // ── Progress ──────────────────────────────────────────────────────────
    val currentStep  = SEQUENCE.getOrNull(stepIndex)
    val totalSteps   = SEQUENCE.size
    val animProgress by animateFloatAsState(
        targetValue   = (stepIndex + 1).toFloat() / totalSteps,
        animationSpec = tween(600),
        label         = "progress",
    )

    // ── Layout ────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Avatar Morph Test", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier            = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // ── 3-D viewport ───────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // ═══════════════════════════════════════════════════════════
                //  SceneView (НЕ ARSceneView) — без ARCore camera passthrough
                //
                //  centerOrigin = Float3(0f,0f,0f) — КРИТИЧНО:
                //     bbox модели Z=1.37..1.76, без centerOrigin голова
                //     висит в 1.5м от камеры-цели → пустой viewport
                //
                //  ARCore meta-data = "optional" в manifest →
                //     arsceneview не инициализирует AR-сессию
                // ═══════════════════════════════════════════════════════════
                Scene(
                    modifier          = Modifier.fillMaxSize(),
                    engine            = engine,
                    modelLoader       = modelLoader,
                    cameraNode        = cameraNode,
                    cameraManipulator = rememberCameraManipulator(
                        orbitHomePosition = Float3(x = 0f, y = -0.02f, z = 2.0f),
                        targetPosition    = Float3(0f, -0.02f, 1.56f)
                    ),
                    environment       = environment,
                    onFrame           = {
                        // Логируем раз в ~2 секунды (каждый 120-й кадр)
                        if (it % 120_000_000_000L < 17_000_000L) {
                            Log.d("AvatarCam", "pos=${cameraNode.position} scale=${cameraNode.scale}")
                        }
                    },
                ) {
                    modelInstance?.let { inst ->
                        ModelNode(
                            modelInstance = inst,
                            autoAnimate   = false,
                        )
                    }
                }

                if (!isFinished) ScanlineOverlay()

                Text(
                    text = if (modelInstance != null) "✓ Model loaded" else "⏳ Loading…",
                    color = if (modelInstance != null) Color(0xFF69F0AE) else Color(0xFFFFD740),
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            // ── Bottom panel ────────────────────────────────────────────────
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
                            targetState    = stepIndex,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label          = "step",
                        ) { idx ->
                            val step = SEQUENCE.getOrNull(idx) ?: return@AnimatedContent
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CategoryChip(step.category)
                                Text(
                                    text       = if (isResetting) "Reset…" else step.label,
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
                            "✓ All $totalSteps steps complete",
                            fontWeight = FontWeight.Bold,
                            color      = Color(0xFF2E7D32),
                        )
                    }

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Step ${(stepIndex + 1).coerceAtMost(totalSteps)} / $totalSteps",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text     = statusText,
                            style    = MaterialTheme.typography.labelMedium,
                            color    = when {
                                statusText.startsWith("OK") ||
                                statusText.contains("loaded") ||
                                statusText.contains("complete") -> Color(0xFF2E7D32)
                                statusText.contains("FAIL") ||
                                statusText.contains("error", ignoreCase = true) -> Color(0xFFD32F2F)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(
                            "${elapsedSec}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    TextButton(onClick = {
                        saveLauncher.launch("avatar_diag_${System.currentTimeMillis()}.txt")
                    }) {
                        Text("📋 Save diagnostic log", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  HELPER COMPOSABLES
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryChip(category: Category) {
    Surface(shape = RoundedCornerShape(6.dp), color = category.color.copy(alpha = 0.15f)) {
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
    val active = weights.indices
        .filter { weights[it] > 0.001f }
        .take(8)
        .map { it to weights[it] }
    if (active.isEmpty()) return
    Row(
        modifier              = Modifier.horizontalScroll(rememberScrollState()),
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
        targetValue   = 0.05f,
        animationSpec = infiniteRepeatable(
            tween(1200, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "a",
    )
    Box(Modifier.fillMaxSize().background(Color(0xFF00E5FF).copy(alpha = alpha)))
}