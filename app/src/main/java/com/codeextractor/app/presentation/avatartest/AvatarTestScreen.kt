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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ══════════════════════════════════════════════════════════════════════════════
//  MODEL CONSTANTS
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
    FACE     ("Face",      Color(0xFFE65100)),
    EMOTION  ("Emotion",   Color(0xFFC62828)),
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
    add(Step.head("Blink Both",   Category.EYES) { set(Idx.eyeBlinkLeft, 1f); set(Idx.eyeBlinkRight, 1f) })
    add(Step.head("Look Left",    Category.EYES) { set(Idx.eyeLookOutLeft, 1f); set(Idx.eyeLookInRight, 1f) })
    add(Step.head("Look Right",   Category.EYES) { set(Idx.eyeLookInLeft, 1f); set(Idx.eyeLookOutRight, 1f) })
    add(Step.head("Brow Down",    Category.BROWS){ set(Idx.browDownLeft, 1f); set(Idx.browDownRight, 1f) })
    add(Step.head("Jaw Open",     Category.JAW)  { set(Idx.jawOpen, 1f) })
    add(Step.head("Smile",        Category.MOUTH){ set(Idx.mouthSmileLeft, 1f); set(Idx.mouthSmileRight, 1f) })
    
    // Emotion showcase
    add(Step.head("Happy 😊", Category.EMOTION, dur = 2_500) {
        set(Idx.mouthSmileLeft, 0.9f); set(Idx.mouthSmileRight, 0.9f)
        set(Idx.cheekSquintLeft, 0.5f); set(Idx.cheekSquintRight, 0.5f)
        set(Idx.browOuterUpLeft, 0.3f); set(Idx.browOuterUpRight, 0.3f)
    })
    add(Step.head("Angry 😠", Category.EMOTION, dur = 2_500) {
        set(Idx.browDownLeft, 1f); set(Idx.browDownRight, 1f)
        set(Idx.noseSneerLeft, 0.5f); set(Idx.noseSneerRight, 0.5f)
        set(Idx.mouthStretchLeft, 0.4f); set(Idx.mouthStretchRight, 0.4f)
        set(Idx.jawForward, 0.3f)
    })
    
    // Всю вашу текущую коллекцию шагов анимаций из SEQUENCE сохраните здесь.
    // ...
    
    add(Step.anim("Built-in Anim 🎬"))
}

// ══════════════════════════════════════════════════════════════════════════════
//  MORPH APPLICATION 
// ══════════════════════════════════════════════════════════════════════════════

private fun ModelNode.applyMorphs(headW: FloatArray) {
    val instance = modelInstance ?: return
    val rm       = engine.renderableManager

    val teethW = FloatArray(5) { i ->
        when(i) {
            0 -> headW[Idx.jawForward]
            1 -> headW[Idx.jawLeft]
            2 -> headW[Idx.jawRight]
            3 -> headW[Idx.jawOpen]
            4 -> headW[Idx.mouthClose]
            else -> 0f
        }
    }
    val eyeLW = FloatArray(4) { i -> headW[Idx.eyeLookDownLeft + i] }
    val eyeRW = FloatArray(4) { i -> headW[Idx.eyeLookDownRight + i] }

    // ЗАЩИТА: Получаем только физические Mesh слои (шкурку головы и глаза) обрезая корневые пустые ноды/суставы.
    val renderables = instance.entities.filter { rm.hasComponent(it) }.map { rm.getInstance(it) }

    renderables.forEachIndexed { i, ri ->
        // Вычисляем натуральную емкость каждого узла сетки у модели
        val targetCapacityCount = rm.getMorphTargetCount(ri)
        if (targetCapacityCount <= 0) return@forEachIndexed

        // Разбор: Какому узлу сколько точек мышц лица подавать
        val w = when {
            targetCapacityCount == 51 -> headW   // Бинго, 100% главная модель лица
            targetCapacityCount == 5  -> teethW  // Нижняя челюсть с 5 суставами
            targetCapacityCount == 4 && i == 2 -> eyeLW // Левый глаз (по дефолт порядку)
            targetCapacityCount == 4 && i == 3 -> eyeRW // Правый Глаз 
            targetCapacityCount == 4 -> eyeLW    // Экстренный переброс
            else -> return@forEachIndexed        // Отвергаем пустые сущности GLB парсера (исключение корутин краша!)
        }

        try {
            // Пихаем выверенные размеры float`ов на сервер движка Vulcan/GL
            rm.setMorphWeights(ri, w, 0)
        } catch(e: Exception) { 
            // Безболезненно давим исключения кадров, не давая "Шагу 1/9" встать колом на экране.
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
    
    // Полноценный бесконечный цикл — не требует импорта CoroutineScope/isActive 
    while (true) {
        val elapsed = System.currentTimeMillis() - startT
        val fraction = (elapsed.toFloat() / durationMs).coerceAtMost(1f)
        
        // Исправленная плавная функция EaseQuadIn/Out (Без pow() ошибки)
        val easeT = if (fraction < 0.5f) {
            2f * fraction * fraction
        } else {
            val f = -2f * fraction + 2f
            1f - (f * f) / 2f
        }
        
        for (i in 0 until 51) {
            tmpArray[i] = currentW[i] + (targetW[i] - currentW[i]) * easeT
        }
        applyMorphs(tmpArray)
        
        if (fraction >= 1f) {
            targetW.copyInto(currentW)
            break // Программный безопасный выход после достижения конца пути
        }
        
        delay(16) // Suspend point гарантированно бросает Cancel при убитом Fragment'e 
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
    
    val currentMorphState = remember { FloatArray(51) }
    var node        by remember { mutableStateOf<ModelNode?>(null) }
    
    // Запускаем жизненный цикл скоупа композа (Для загрузчика Android View IO)
    val scope       = rememberCoroutineScope() 

    val currentStep  = SEQUENCE.getOrNull(stepIndex)
    val totalSteps   = SEQUENCE.size
    val animProgress by animateFloatAsState(
        targetValue   = stepIndex.toFloat() / totalSteps,
        animationSpec = tween(600),
        label         = "progress",
    )

    // Timer (Зацикленность только пока мы не дойдем до Finished состояния)
    LaunchedEffect(isFinished) {
        while (!isFinished) { delay(1_000); elapsedSec++ }
    }

    // Test loop 
    LaunchedEffect(node) {
        val n = node ?: return@LaunchedEffect
        SEQUENCE.forEachIndexed { i, step ->
            stepIndex   = i
            isResetting = false
            if (step.headWeights == null) {
                n.animateMorphsSmooth(currentMorphState, FloatArray(51), 300)
                n.playAnimation(0)
                delay(step.durationMs)
                n.stopAnimation(0)
            } else {
                n.animateMorphsSmooth(currentMorphState, step.headWeights, 450)
                delay((step.durationMs - 450).coerceAtLeast(0))
                isResetting = true
                n.animateMorphsSmooth(currentMorphState, FloatArray(51), 400)
                delay(100)
            }
        }
        isFinished = true
    }

    // Дебаггинг чистки стейтов на Back. Оптимизация на выход! 
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

            // ── 3D Viewport ───────────────────────────────────────────────
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
                            // Фон серого оттенка
                            setBackgroundColor(android.graphics.Color.parseColor("#151515")) 

                            scope.launch {
                                // Даем время Nav-системе UI запустить холст 
                                delay(300) 
                                
                                try {
                                    val asyncInstance = modelLoader.createModelInstance(
                                        assetFileLocation = "models/source_named.glb"
                                    )
                                    
                                    if (asyncInstance != null) {
                                        val modelNode = ModelNode(
                                            modelInstance = asyncInstance as io.github.sceneview.model.ModelInstance,
                                            scaleToUnits  = 0.45f, 
                                        )
                                        
                                        // Опускаем аватар вниз, отталкиваем назад (Обязательное условие рендера!) 
                                        modelNode.position = io.github.sceneview.math.Position(x = 0f, y = -0.1f, z = -1.5f) 
                                        
                                        addChildNode(modelNode)
                                        node = modelNode 
                                    }
                                } catch (e: Exception) { 
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

            // ── HUD ───────────────────────────────────────────────────────
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
                            targetState   = currentStep.label,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label         = "step_label",
                        ) { label ->
                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
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

                        if (currentStep.headWeights != null && !isResetting) {
                            ActiveWeightsRow(currentStep.headWeights)
                        }
                    }

                    if (isFinished) {
                        Text(
                            text       = "✅ All $totalSteps steps complete",
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
        if (weights[i] > 0.001f) {
            active.add(Pair(i, weights[i]))
            if (active.size >= 6) break
        }
    }
    if (active.isEmpty()) return

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (pair in active) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text     = "#${pair.first}=${"%.2f".format(pair.second)}",
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