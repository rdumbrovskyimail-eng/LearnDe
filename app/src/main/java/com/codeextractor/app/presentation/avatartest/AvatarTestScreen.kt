package com.codeextractor.app.presentation.avatartest

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "AvatarTest"
private const val MODEL_PATH = "models/source_named.glb"

// ══════════════════════════════════════════════════════════════════════════════
//  DIAGNOSTIC LOGGER — собирает все логи в память, потом сохраняет в файл
// ══════════════════════════════════════════════════════════════════════════════

private object DiagLog {
    private val lines = CopyOnWriteArrayList<String>()
    private val startTime = System.currentTimeMillis()

    fun log(level: String, tag: String, msg: String) {
        val ts = System.currentTimeMillis() - startTime
        val entry = "[${ts}ms] [$level/$tag] $msg"
        lines.add(entry)
        when (level) {
            "E" -> Log.e(tag, msg)
            "W" -> Log.w(tag, msg)
            "I" -> Log.i(tag, msg)
            else -> Log.d(tag, msg)
        }
    }

    fun d(msg: String) = log("D", TAG, msg)
    fun i(msg: String) = log("I", TAG, msg)
    fun w(msg: String) = log("W", TAG, msg)
    fun e(msg: String) = log("E", TAG, msg)

    fun getFullLog(): String {
        val header = buildString {
            appendLine("═══════════════════════════════════════════")
            appendLine("  AVATAR TEST DIAGNOSTIC LOG")
            appendLine("  Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("  Total entries: ${lines.size}")
            appendLine("═══════════════════════════════════════════")
            appendLine()
        }
        return header + lines.joinToString("\n")
    }

    fun clear() = lines.clear()
}

// ══════════════════════════════════════════════════════════════════════════════
//  ASSET VALIDATOR — проверяет GLB файл прямо из assets
// ══════════════════════════════════════════════════════════════════════════════

private fun validateGlbAsset(context: Context, path: String): String {
    DiagLog.i("╔══ ASSET VALIDATION START: $path ══╗")
    return try {
        // 1) Проверяем что файл вообще открывается
        val inputStream = context.assets.open(path)
        DiagLog.d("  assets.open() — OK")

        // 2) Читаем первые 12 байт (GLB header)
        val header = ByteArray(12)
        val bytesRead = inputStream.read(header)
        DiagLog.d("  header bytes read: $bytesRead")

        // 3) Считаем полный размер
        val available = inputStream.available()
        // Для больших файлов available() может вернуть 0 или неполное значение,
        // поэтому читаем всё
        var totalSize = bytesRead.toLong()
        val buf = ByteArray(8192)
        var nonZeroBytes = 0L
        // Проверяем первые 12 байт на нули
        for (b in header) { if (b != 0.toByte()) nonZeroBytes++ }

        while (true) {
            val n = inputStream.read(buf)
            if (n <= 0) break
            totalSize += n
            for (i in 0 until n) { if (buf[i] != 0.toByte()) nonZeroBytes++ }
        }
        inputStream.close()

        DiagLog.d("  total size: $totalSize bytes (${totalSize / 1024}KB)")
        DiagLog.d("  non-zero bytes: $nonZeroBytes / $totalSize")

        // 4) Проверяем GLB magic
        val magic = String(header, 0, 4, Charsets.US_ASCII)
        val isGlb = header[0] == 0x67.toByte() && header[1] == 0x6C.toByte() &&
                header[2] == 0x54.toByte() && header[3] == 0x46.toByte()
        DiagLog.d("  magic bytes: ${header.take(4).joinToString(" ") { "0x%02X".format(it) }}")
        DiagLog.d("  magic string: '$magic'")
        DiagLog.d("  is valid GLB: $isGlb")

        if (isGlb && bytesRead >= 12) {
            val version = (header[4].toInt() and 0xFF) or
                    ((header[5].toInt() and 0xFF) shl 8) or
                    ((header[6].toInt() and 0xFF) shl 16) or
                    ((header[7].toInt() and 0xFF) shl 24)
            val fileLength = (header[8].toInt() and 0xFF) or
                    ((header[9].toInt() and 0xFF) shl 8) or
                    ((header[10].toInt() and 0xFF) shl 16) or
                    ((header[11].toInt() and 0xFF) shl 24)
            DiagLog.d("  GLB version: $version")
            DiagLog.d("  GLB declared length: $fileLength")
        }

        if (nonZeroBytes == 0L) {
            DiagLog.e("  ✖ FILE IS ALL ZEROS! Model is corrupted!")
            "FAIL: All zeros ($totalSize bytes)"
        } else if (!isGlb) {
            DiagLog.e("  ✖ NOT A VALID GLB FILE! Magic: $magic")
            "FAIL: Not GLB (magic=$magic, ${totalSize}b)"
        } else {
            DiagLog.i("  ✔ GLB file looks valid: ${totalSize}b, ${nonZeroBytes} non-zero")
            "OK: GLB ${totalSize / 1024}KB"
        }
    } catch (e: Exception) {
        DiagLog.e("  ✖ EXCEPTION: ${e::class.simpleName}: ${e.message}")
        "FAIL: ${e::class.simpleName}: ${e.message}"
    } finally {
        DiagLog.i("╚══ ASSET VALIDATION END ══╝")
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Список всех assets в models/
// ══════════════════════════════════════════════════════════════════════════════

private fun listAssets(context: Context) {
    DiagLog.i("╔══ LISTING ASSETS ══╗")
    try {
        val root = context.assets.list("") ?: emptyArray()
        DiagLog.d("  root: ${root.joinToString(", ")}")

        if ("models" in root) {
            val models = context.assets.list("models") ?: emptyArray()
            DiagLog.d("  models/: ${models.joinToString(", ")}")
            models.forEach { name ->
                try {
                    val s = context.assets.open("models/$name")
                    val size = s.available()
                    s.close()
                    DiagLog.d("    models/$name → ${size}b available")
                } catch (e: Exception) {
                    DiagLog.w("    models/$name → ${e.message}")
                }
            }
        } else {
            DiagLog.w("  'models' directory NOT FOUND in assets!")
            // Пробуем другие папки
            root.forEach { dir ->
                try {
                    val sub = context.assets.list(dir)
                    if (sub != null && sub.isNotEmpty()) {
                        DiagLog.d("  $dir/: ${sub.take(10).joinToString(", ")}${if (sub.size > 10) "..." else ""}")
                    }
                } catch (_: Exception) {}
            }
        }
    } catch (e: Exception) {
        DiagLog.e("  Exception listing assets: ${e.message}")
    }
    DiagLog.i("╚══ END LISTING ══╝")
}

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
//  MORPH APPLICATION — работает с ModelInstance + Engine
// ══════════════════════════════════════════════════════════════════════════════

private fun applyMorphsToInstance(engine: Engine, instance: ModelInstance, headW: FloatArray) {
    val rm = engine.renderableManager
    val teethW = floatArrayOf(
        headW[Idx.jawForward], headW[Idx.jawLeft], headW[Idx.jawRight],
        headW[Idx.jawOpen], headW[Idx.mouthClose]
    )
    val eyeLW = floatArrayOf(
        headW[Idx.eyeLookDownLeft], headW[Idx.eyeLookInLeft],
        headW[Idx.eyeLookOutLeft], headW[Idx.eyeLookUpLeft]
    )
    val eyeRW = floatArrayOf(
        headW[Idx.eyeLookDownRight], headW[Idx.eyeLookInRight],
        headW[Idx.eyeLookOutRight], headW[Idx.eyeLookUpRight]
    )
    val renderables = instance.entities.filter { rm.hasComponent(it) }.map { rm.getInstance(it) }
    var fourIdx = 0
    renderables.forEach { ri ->
        val count = rm.getMorphTargetCount(ri)
        if (count <= 0) return@forEach
        val w = when (count) {
            51 -> headW
            5 -> teethW
            4 -> { val r = if (fourIdx == 0) eyeLW else eyeRW; fourIdx++; r }
            else -> return@forEach
        }
        try { rm.setMorphWeights(ri, w, 0) } catch (ex: Exception) {
            DiagLog.e("setMorphWeights failed: count=$count err=${ex.message}")
        }
    }
}

private suspend fun animateMorphsSmooth(
    engine: Engine, instance: ModelInstance, cur: FloatArray, tgt: FloatArray, durMs: Long
) {
    val start = System.currentTimeMillis()
    val tmp = FloatArray(51)
    while (true) {
        val frac = ((System.currentTimeMillis() - start).toFloat() / durMs).coerceAtMost(1f)
        val t = if (frac < 0.5f) 2f * frac * frac else 1f - (-2f * frac + 2f).let { it * it } / 2f
        for (i in 0 until 51) tmp[i] = cur[i] + (tgt[i] - cur[i]) * t
        applyMorphsToInstance(engine, instance, tmp)
        if (frac >= 1f) { tgt.copyInto(cur); break }
        delay(16)
    }
}

private suspend fun playAnimationManual(instance: ModelInstance, durationMs: Long) {
    DiagLog.d("playAnimationManual: trying to get animator...")
    val animator = instance.animator
    DiagLog.d("playAnimationManual: animator=$animator")
    if (animator == null) throw IllegalStateException("No animator on ModelInstance")
    val animCount = animator.animationCount
    DiagLog.d("playAnimationManual: animationCount=$animCount")
    if (animCount == 0) throw IllegalStateException("No animations in model (count=0)")
    val animDuration = animator.getAnimationDuration(0)
    DiagLog.d("playAnimationManual: anim[0] duration=${animDuration}s")
    val maxMs = minOf(durationMs, (animDuration * 1000).toLong())
    val start = System.currentTimeMillis()
    while (true) {
        val elapsed = System.currentTimeMillis() - start
        if (elapsed >= maxMs) break
        animator.applyAnimation(0, elapsed / 1000f)
        animator.updateBoneMatrices()
        delay(16)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  SCREEN
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarTestScreen(onBack: () -> Unit) {

    val context = LocalContext.current

    var stepIndex   by remember { mutableIntStateOf(0) }
    var isResetting by remember { mutableStateOf(false) }
    var elapsedSec  by remember { mutableIntStateOf(0) }
    var isFinished  by remember { mutableStateOf(false) }
    var statusText  by remember { mutableStateOf("Initializing…") }
    var showSaveDialog by remember { mutableStateOf(false) }

    val currentMorphState = remember { FloatArray(51) }

    // ── ModelInstance ref — заполняется ИЗНУТРИ Scene ──────────────────────
    var modelInstanceRef by remember { mutableStateOf<ModelInstance?>(null) }

    // ── Engine & Loaders ──────────────────────────────────────────────────
    DiagLog.d("Composable: creating engine & loaders")
    val engine            = rememberEngine()
    val modelLoader       = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    DiagLog.d("Composable: engine=$engine, modelLoader=$modelLoader")

    val environment = remember(environmentLoader) {
        DiagLog.d("Creating environment...")
        try {
            val env = environmentLoader.createHDREnvironment("environments/studio_small_09_2k.hdr")
            if (env != null) {
                DiagLog.i("HDR environment created OK")
                env
            } else {
                DiagLog.w("HDR env returned null, using default")
                environmentLoader.createEnvironment()!!
            }
        } catch (e: Exception) {
            DiagLog.w("HDR env exception: ${e.message}, using default")
            environmentLoader.createEnvironment()!!
        }
    }

    val mainLightNode = rememberMainLightNode(engine) {
        intensity = 100_000f
        DiagLog.d("MainLightNode created, intensity=100000")
    }

    val cameraNode = rememberCameraNode(engine) {
        position = Position(z = 2.5f)
        DiagLog.d("CameraNode created, position z=2.5")
    }

    // ── Asset validation при первом запуске ───────────────────────────────
    LaunchedEffect(Unit) {
        DiagLog.clear()
        DiagLog.i("╔══════════════════════════════════════════╗")
        DiagLog.i("║     AVATAR TEST DIAGNOSTIC SESSION       ║")
        DiagLog.i("╚══════════════════════════════════════════╝")
        DiagLog.d("Android SDK: ${android.os.Build.VERSION.SDK_INT}")
        DiagLog.d("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        DiagLog.d("SceneView arsceneview:3.5.2")
        DiagLog.d("Model path: $MODEL_PATH")

        listAssets(context)
        val validationResult = validateGlbAsset(context, MODEL_PATH)
        statusText = "Asset: $validationResult"

        DiagLog.d("Waiting for model to load via rememberModelInstance...")
    }

    // ── Таймер ────────────────────────────────────────────────────────────
    LaunchedEffect(isFinished) {
        while (!isFinished) { delay(1_000); elapsedSec++ }
    }

    // ── Авто-показ диалога сохранения через 50с ──────────────────────────
    LaunchedEffect(Unit) {
        delay(50_000)
        DiagLog.i("50s elapsed — triggering save dialog")
        showSaveDialog = true
    }

    // ── SAF launcher для сохранения лога ─────────────────────────────────
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(DiagLog.getFullLog().toByteArray())
                }
                DiagLog.i("Log saved to: $uri")
                statusText = "Log saved!"
            } catch (e: Exception) {
                DiagLog.e("Failed to save log: ${e.message}")
            }
        }
    }

    // ── Прогресс ──────────────────────────────────────────────────────────
    val currentStep  = SEQUENCE.getOrNull(stepIndex)
    val totalSteps   = SEQUENCE.size
    val animProgress by animateFloatAsState(
        targetValue   = (stepIndex + 1).toFloat() / totalSteps,
        animationSpec = tween(600), label = "progress",
    )

    // ── Запуск теста когда modelInstance готов ─────────────────────────────
    LaunchedEffect(modelInstanceRef) {
        val inst = modelInstanceRef
        if (inst == null) {
            DiagLog.d("LaunchedEffect(modelInstanceRef): still null, waiting...")
            return@LaunchedEffect
        }

        DiagLog.i("╔══ MODEL INSTANCE READY ══╗")
        DiagLog.d("  entities count: ${inst.entities.size}")
        DiagLog.d("  animator: ${inst.animator}")
        DiagLog.d("  animator?.animationCount: ${inst.animator?.animationCount}")

        // Проверяем morph targets
        val rm = engine.renderableManager
        inst.entities.forEachIndexed { idx, entity ->
            val hasComp = rm.hasComponent(entity)
            if (hasComp) {
                val ri = rm.getInstance(entity)
                val morphCount = rm.getMorphTargetCount(ri)
                DiagLog.d("  entity[$idx]=$entity renderable morphTargets=$morphCount")
            } else {
                DiagLog.d("  entity[$idx]=$entity — no renderable component")
            }
        }

        statusText = "Model loaded! Starting test..."
        DiagLog.i("═══ TEST SEQUENCE START ═══")
        delay(500)

        SEQUENCE.forEachIndexed { i, step ->
            stepIndex = i; isResetting = false
            statusText = step.label
            DiagLog.d("Step[$i]: ${step.category.label} — ${step.label}")

            if (step.headWeights == null) {
                DiagLog.d("  → Animation step")
                animateMorphsSmooth(engine, inst, currentMorphState, FloatArray(51), 300)
                try {
                    playAnimationManual(inst, step.durationMs)
                    statusText = "OK: Anim played"
                    DiagLog.i("  → Animation OK")
                } catch (e: Exception) {
                    statusText = "FAIL: ${e.message}"
                    DiagLog.e("  → Animation FAIL: ${e.message}")
                    delay(step.durationMs)
                }
            } else {
                DiagLog.d("  → Morph step: active=${step.headWeights.count { it > 0f }}")
                animateMorphsSmooth(engine, inst, currentMorphState, step.headWeights, 450)
                statusText = "OK: ${step.label}"
                delay((step.durationMs - 450).coerceAtLeast(200))
                isResetting = true
                animateMorphsSmooth(engine, inst, currentMorphState, FloatArray(51), 400)
                delay(100)
            }
        }
        DiagLog.i("═══ TEST SEQUENCE COMPLETE ═══")
        statusText = "All tests complete"
        isFinished = true
        showSaveDialog = true
    }

    // ── Save dialog ──────────────────────────────────────────────────────
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Diagnostic Log") },
            text = {
                Text("${DiagLog.getFullLog().lines().size} log entries collected.\nModel: ${if (modelInstanceRef != null) "LOADED" else "NOT LOADED"}\nElapsed: ${elapsedSec}s")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    saveLauncher.launch("avatar_test_${System.currentTimeMillis()}.txt")
                }) { Text("Save to file") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Later") }
            }
        )
    }

    // ── UI ────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Avatar Test DIAG", fontWeight = FontWeight.SemiBold) },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                // ═══ SceneView 3.x — trailing content lambda ═══
                Scene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    environmentLoader = environmentLoader,
                    environment = environment,
                    mainLightNode = mainLightNode,
                    cameraNode = cameraNode,
                ) {
                    // rememberModelInstance ВНУТРИ Scene content lambda!
                    val instance = rememberModelInstance(
                        modelLoader = modelLoader,
                        assetFileLocation = MODEL_PATH,
                    )

                    // Пробрасываем наружу через state
                    LaunchedEffect(instance) {
                        DiagLog.d("Scene content: rememberModelInstance result = $instance")
                        if (instance != null) {
                            DiagLog.i("Scene content: MODEL INSTANCE LOADED!")
                            DiagLog.d("  entities: ${instance.entities.size}")
                            modelInstanceRef = instance
                        } else {
                            DiagLog.d("Scene content: instance is null (still loading or failed)")
                        }
                    }

                    instance?.let { inst ->
                        DiagLog.d("Scene content: rendering ModelNode")
                        ModelNode(
                            modelInstance = inst,
                            scaleToUnits = 0.8f,
                            autoAnimate = false,
                        )
                    }
                }

                if (!isFinished) ScanlineOverlay()

                // Overlay с текущим статусом прямо на Scene
                Text(
                    text = if (modelInstanceRef != null) "✓ Model OK" else "⏳ Loading...",
                    color = if (modelInstanceRef != null) Color.Green else Color.Yellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(4.dp)
                )
            }

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
                        progress   = { animProgress },
                        modifier   = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        strokeCap  = StrokeCap.Round,
                        color      = currentStep?.category?.color ?: MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
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
                            text     = statusText,
                            style    = MaterialTheme.typography.labelMedium,
                            color    = if (statusText.startsWith("OK") || statusText.contains("loaded") || statusText.contains("complete"))
                                           Color(0xFF2E7D32)
                                       else if (statusText.contains("FAIL") || statusText.contains("error", ignoreCase = true))
                                           Color(0xFFD32F2F)
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(
                            text  = "${elapsedSec}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Кнопка ручного сохранения лога
                    TextButton(
                        onClick = {
                            saveLauncher.launch("avatar_diag_${System.currentTimeMillis()}.txt")
                        }
                    ) {
                        Text("📋 Save log now", fontSize = 11.sp)
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
    val active = weights.indices.filter { weights[it] > 0.001f }.take(8).map { it to weights[it] }
    if (active.isEmpty()) return
    Row(
        modifier              = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((idx, value) in active) {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
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
        0f, 0.06f,
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "a",
    )
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF00E5FF).copy(alpha = alpha)))
}