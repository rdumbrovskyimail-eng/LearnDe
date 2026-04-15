package com.codeextractor.app.presentation.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.codeextractor.app.domain.avatar.ARKit
import com.codeextractor.app.domain.avatar.RenderDoubleBuffer
import com.codeextractor.app.domain.avatar.ZeroAllocRenderState
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "AvatarScene"

// ── Базовые модели (разные для каждого аватара) ──────────────────────────────
private const val BASE_MODEL_PATH_1 = "models/test.glb"
private const val BASE_MODEL_PATH_2 = "models/test2.glb"

// ── Ресурсы аватара 1 (мужской) ──────────────────────────────────────────────
private const val HEAD_TEXTURE_1  = "models/head_texture.png"
private const val EYES_TEXTURE_1  = "models/eyes_texture.png"
private const val TEETH_TEXTURE_1 = "models/teeth_texture.png"

// ── Ресурсы аватара 2 (женский) ──────────────────────────────────────────────
private const val HEAD_TEXTURE_2  = "models/head_texture2.png"
private const val EYES_TEXTURE_2  = "models/eyes_texture2.png"
private const val TEETH_TEXTURE_2 = "models/teeth_texture2.png"

private const val COMPOSITE_SIZE = 1024

// ── Камера ───────────────────────────────────────────────────────────────────
private val CAM_POS = dev.romainguy.kotlin.math.Float3(0f, 1.35f, 0.70f)
private val CAM_TGT = dev.romainguy.kotlin.math.Float3(0f, 1.35f, 0.00f)
private const val MODEL_SCALE = 0.35f

// ── Pre-allocated array для поворота головы (zero-alloc в onFrame) ────────────
private val reusableTransformMatrix = FloatArray(16)

// ── Максимальные значения blendshape для глаз (чтобы не вылезли из черепа) ────
private const val EYE_LOOK_MAX = 0.75f

/**
 * AvatarScene — 3D Avatar Renderer
 *
 * АРХИТЕКТУРА:
 *   1. Загрузка модели — LaunchedEffect(modelLoader, avatarIndex)
 *   2. Настройка материалов — LaunchedEffect(modelInstance, avatarIndex)
 *   3. Рендер-цикл — Scene.onFrame (60 fps):
 *      • applyMorphWeights — ARKit blend-shapes (губы, моргание, мимика)
 *        + коррекция взгляда: глаза всегда смотрят на камеру
 *      • applyHeadRotation — поворот root entity
 *
 * ГЛАЗА: glb-меши глаз являются дочерними объектами черепа и вращаются
 * вместе с ним. Направление взгляда компенсируется через ARKit blend-shapes
 * (eyeLookUp/Down/In/Out) — при повороте головы глаза смотрят обратно
 * на камеру. Значения ограничены EYE_LOOK_MAX чтобы глаза не вылезали.
 *
 * PBR МАТЕРИАЛЫ:
 *   Голова  — roughness 0.48 (матовая кожа)
 *   Зубы    — roughness 0.35 с текстурой / 0.85 (слизистая) без текстуры
 *   Глаза   — roughness 0.02 (роговица)
 */
@Composable
fun AvatarScene(
    modifier: Modifier = Modifier,
    renderBuffer: RenderDoubleBuffer? = null,
) {
    val ctx               = LocalContext.current
    val engine            = rememberEngine()
    val modelLoader       = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment       = rememberEnvironment(environmentLoader)
    val cameraNode        = rememberCameraNode(engine) { position = CAM_POS }

    // ── Текущий аватар: 1 = мужской, 2 = женский ────────────────────────────
    var avatarIndex    by remember { mutableStateOf(1) }
    var modelInstance  by remember { mutableStateOf<ModelInstance?>(null) }
    var materialsReady by remember { mutableStateOf(false) }

    val textureCache    = remember { mutableMapOf<String, Texture>() }
    val frameSnapshot   = remember { ZeroAllocRenderState() }
    var whiteTex        by remember { mutableStateOf<Texture?>(null) }

    fun modelPath() = if (avatarIndex == 1) BASE_MODEL_PATH_1 else BASE_MODEL_PATH_2
    fun headTex()   = if (avatarIndex == 1) HEAD_TEXTURE_1  else HEAD_TEXTURE_2
    fun eyesTex()   = if (avatarIndex == 1) EYES_TEXTURE_1  else EYES_TEXTURE_2
    fun teethTex()  = if (avatarIndex == 1) TEETH_TEXTURE_1 else TEETH_TEXTURE_2

    // ── Очистка GPU-памяти при выходе с экрана ───────────────────────────────
    DisposableEffect(engine) {
        onDispose {
            Log.d(TAG, "Disposing AvatarScene")
            // Безопасно удаляем все текстуры только при полном закрытии экрана
            textureCache.values.forEach { 
                try { engine.destroyTexture(it) } catch (e: Exception) {} 
            }
            textureCache.clear()
            whiteTex?.let { try { engine.destroyTexture(it) } catch (e: Exception) {} }
            whiteTex = null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ЗАГРУЗКА БАЗОВОЙ МОДЕЛИ (ПЕРЕЗАПУСКАЕТСЯ ПРИ СМЕНЕ АВАТАРА)
    // ═══════════════════════════════════════════════════════════════════════════
    LaunchedEffect(modelLoader, avatarIndex) {
        modelInstance  = null
        materialsReady = false

        val buffer = withContext(Dispatchers.IO) {
            val editorOutput = File(ctx.cacheDir, "patched_model.glb")
            // 2. Разделяем кэш для двух аватаров, чтобы не патчить их каждый раз
            val patchedFile  = File(ctx.cacheDir, "patched_model_base_$avatarIndex.glb")

            if (!patchedFile.exists()) {
                com.codeextractor.app.editor.GlbTextureEditor(ctx)
                    .preparePatchedModel(modelPath()) // <-- Используем нужный путь

                if (editorOutput.exists()) {
                    editorOutput.renameTo(patchedFile)
                }
            }

            val bytes = patchedFile.readBytes()
            ByteBuffer.allocateDirect(bytes.size).also { it.put(bytes); it.rewind() }
        }
        
        modelInstance = modelLoader.createModelInstance(buffer)
        Log.d(TAG, "Model loaded for avatar $avatarIndex: ${modelInstance?.entities?.size} entities")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  НАСТРОЙКА МАТЕРИАЛОВ И СМЕНА ТЕКСТУР (С КЭШИРОВАНИЕМ)
    // ═══════════════════════════════════════════════════════════════════════════
    LaunchedEffect(modelInstance) {
        val mi = modelInstance ?: return@LaunchedEffect
        val rm = engine.renderableManager

        materialsReady = false

        if (whiteTex == null) {
            whiteTex = buildWhiteTexture(engine)
        }
        val defaultSampler = buildDefaultSampler()

        withContext(Dispatchers.IO) {
            var headMat:  MaterialInstance? = null
            var teethMat: MaterialInstance? = null
            var eyeLMat:  MaterialInstance? = null
            var eyeRMat:  MaterialInstance? = null
            var eyeCount = 0

            for (entity in mi.entities) {
                if (!rm.hasComponent(entity)) continue
                val ri         = rm.getInstance(entity)
                val morphCount = try { rm.getMorphTargetCount(ri) } catch (_: Exception) { 0 }
                val primCount  = rm.getPrimitiveCount(ri)
                if (primCount <= 0 || morphCount <= 0) continue

                val mat = try { rm.getMaterialInstanceAt(ri, 0) } catch (_: Exception) { null } ?: continue

                when (identifyMeshType(mi, entity, morphCount, eyeCount)) {
                    ARKit.MeshType.HEAD  -> headMat  = mat
                    ARKit.MeshType.TEETH -> teethMat = mat
                    ARKit.MeshType.EYE_LEFT,
                    ARKit.MeshType.EYE_RIGHT -> {
                        if (eyeCount == 0) eyeLMat = mat else eyeRMat = mat
                        eyeCount++
                    }
                    ARKit.MeshType.OTHER -> { /* не трогаем */ }
                }
            }

            // ── ГОЛОВА ──
            headMat?.let { mat ->
                val key = "head_$avatarIndex"
                val tex = textureCache[key] ?: buildHeadCompositeTexture(ctx, engine, headTex())?.also { textureCache[key] = it }
                
                if (tex != null) {
                    setParam(mat, "baseColorMap", tex, buildMipmapSampler(anisotropy = 8f))
                }
                setParam(mat, "baseColorFactor", 1f, 1f, 1f, 1f)
                setParam(mat, "roughnessFactor", 0.48f)
                setParam(mat, "metallicFactor",  0.00f)
            }

            // ── ЗУБЫ ──
            teethMat?.let { mat ->
                val key = "teeth_$avatarIndex"
                val tex = textureCache[key] ?: loadTexture(ctx, engine, teethTex(), mipmap = true)?.also { textureCache[key] = it }
                
                if (tex != null) {
                    setParam(mat, "baseColorMap", tex, buildMipmapSampler())
                    setParam(mat, "baseColorFactor", 0.97f, 0.97f, 0.95f, 1f)
                    setParam(mat, "roughnessFactor", 0.35f)
                } else {
                    setParam(mat, "baseColorMap", whiteTex!!, defaultSampler)
                    setParam(mat, "baseColorFactor", 0.55f, 0.22f, 0.20f, 1f)
                    setParam(mat, "roughnessFactor", 0.85f)
                }
                setParam(mat, "metallicFactor",  0.00f)
            }

            // ── ГЛАЗА ──
            val key = "eyes_$avatarIndex"
            val eyeTex = textureCache[key] ?: loadTexture(ctx, engine, eyesTex(), mipmap = true)?.also { textureCache[key] = it }
            
            eyeTex?.let { tex ->
                val sampler = buildMipmapSampler(wrap = TextureSampler.WrapMode.REPEAT)
                listOf(eyeLMat, eyeRMat).filterNotNull().forEach { mat ->
                    setParam(mat, "baseColorMap", tex, sampler)
                    setParam(mat, "baseColorFactor", 1f, 1f, 1f, 1f)
                    setParam(mat, "roughnessFactor", 0.02f)
                    setParam(mat, "metallicFactor",  0.00f)
                }
            }
        }

        engine.flushAndWait()
        materialsReady = true
        Log.d(TAG, "Materials ready [avatar=$avatarIndex]. Cached textures: ${textureCache.size}")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  RENDER
    // ═══════════════════════════════════════════════════════════════════════════
    Box(modifier = modifier.background(Color.Black)) {

        Scene(
            modifier    = Modifier.fillMaxSize(),
            engine      = engine,
            modelLoader = modelLoader,
            cameraNode  = cameraNode,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = CAM_POS,
                targetPosition    = CAM_TGT,
            ),
            environment = environment,
            onFrame = {
                val mi = modelInstance ?: return@Scene
                if (!materialsReady) return@Scene

                renderBuffer?.read(frameSnapshot)

                // 1. Blend-shapes: мимика, моргание, рот и т.д.
                //    + коррекция взгляда на камеру с учётом поворота головы
                applyMorphWeights(
                    engine, mi, frameSnapshot,
                    frameSnapshot.headPitch,
                    frameSnapshot.headYaw,
                )

                // 2. Поворот головы (глаза — дочерние меши, вращаются вместе)
                applyHeadRotation(
                    engine, mi,
                    frameSnapshot.headPitch,
                    frameSnapshot.headYaw,
                    frameSnapshot.headRoll,
                )
            },
        ) {
            modelInstance?.let { mi ->
                ModelNode(
                    modelInstance = mi,
                    scaleToUnits  = MODEL_SCALE,
                    centerOrigin  = Position(0f, -0.65f, 0f),
                    autoAnimate   = false,
                )
            }
        }

        // ── Индикатор загрузки ───────────────────────────────────────────────
        if (modelInstance == null || !materialsReady) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White.copy(alpha = 0.35f),
            )
        }

        // ── Кнопка переключения аватара ──────────────────────────────────────
        IconButton(
            onClick = { avatarIndex = if (avatarIndex == 1) 2 else 1 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(48.dp)
                .background(color = Color.White.copy(alpha = 0.15f), shape = CircleShape),
        ) {
            Text(
                text     = if (avatarIndex == 1) "♀" else "♂",
                color    = Color.White,
                fontSize = TextUnit(22f, TextUnitType.Sp),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  MORPH APPLICATION + EYE GAZE CORRECTION
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Применяет blend-shapes и корректирует направление взгляда.
 *
 * Глаза — дочерние объекты головы и вращаются вместе с ней. Чтобы взгляд
 * всегда был направлен на камеру, мы добавляем компенсирующие значения
 * в blendshapes eyeLookUp/Down/In/Out, пропорциональные углу поворота головы.
 *
 * headPitchDeg > 0 → голова наклонена вниз → глаза смотрят вверх (компенсация)
 * headYawDeg   > 0 → голова повёрнута вправо → глаза смотрят влево (компенсация)
 *
 * Все значения ограничены [0, EYE_LOOK_MAX] чтобы глаза не вылезали из орбит.
 */
private fun applyMorphWeights(
    engine:       com.google.android.filament.Engine,
    instance:     ModelInstance,
    state:        ZeroAllocRenderState,
    headPitchDeg: Float,
    headYawDeg:   Float,
) {
    val rm   = engine.renderableManager
    val head = state.morphWeights

    // ── Коэффициенты компенсации (подобраны эмпирически) ─────────────────
    val gazePerDeg = 0.020f

    // Вертикальная компенсация: pitch > 0 → голова вниз → глаза вверх
    val pitchComp = headPitchDeg * gazePerDeg
    val eyeUpComp   = (-pitchComp).coerceAtLeast(0f)
    val eyeDownComp = pitchComp.coerceAtLeast(0f)

    // Горизонтальная компенсация: yaw > 0 → голова вправо
    val yawComp = headYawDeg * gazePerDeg

    val eyeLInComp  = yawComp.coerceAtLeast(0f)
    val eyeLOutComp = (-yawComp).coerceAtLeast(0f)

    val eyeROutComp = yawComp.coerceAtLeast(0f)
    val eyeRInComp  = (-yawComp).coerceAtLeast(0f)

    // ── Собираем веса для зубов ──────────────────────────────────────────
    val teethW = FloatArray(5) { i -> head[ARKit.TEETH_SOURCE_INDICES[i]] }

    // ── Собираем веса для глаз с компенсацией ────────────────────────────
    val eyeLW = FloatArray(4) { i ->
        val base = head[ARKit.EYE_SOURCE_INDICES[i]]
        val comp = when (i) {
            0 -> eyeDownComp
            1 -> eyeLInComp
            2 -> eyeLOutComp
            3 -> eyeUpComp
            else -> 0f
        }
        (base + comp).coerceIn(0f, EYE_LOOK_MAX)
    }

    val eyeRW = FloatArray(4) { i ->
        val base = head[ARKit.EYE_SOURCE_INDICES[i] + ARKit.EYE_RIGHT_OFFSET]
        val comp = when (i) {
            0 -> eyeDownComp
            1 -> eyeRInComp
            2 -> eyeROutComp
            3 -> eyeUpComp
            else -> 0f
        }
        (base + comp).coerceIn(0f, EYE_LOOK_MAX)
    }

    var eyeIdx = 0

    for (entity in instance.entities) {
        if (!rm.hasComponent(entity)) continue
        val ri    = rm.getInstance(entity)
        val count = try { rm.getMorphTargetCount(ri) } catch (_: Exception) { 0 }
        if (count <= 0) continue

        val w = when (count) {
            ARKit.COUNT -> head
            5           -> teethW
            4           -> if (eyeIdx++ == 0) eyeLW else eyeRW
            else        -> continue
        }

        try { rm.setMorphWeights(ri, w, 0) } catch (_: Exception) {}
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  HEAD ROTATION  (zero-alloc)
// ═══════════════════════════════════════════════════════════════════════════════

private fun applyHeadRotation(
    engine:   com.google.android.filament.Engine,
    instance: ModelInstance,
    pitchDeg: Float,
    yawDeg:   Float,
    rollDeg:  Float,
) {
    if (kotlin.math.abs(pitchDeg) < 0.04f &&
        kotlin.math.abs(yawDeg)   < 0.04f &&
        kotlin.math.abs(rollDeg)  < 0.04f) return

    val tm         = engine.transformManager
    val rootEntity = instance.root
    if (!tm.hasComponent(rootEntity)) return
    val ti = tm.getInstance(rootEntity)

    val mat = reusableTransformMatrix
    tm.getTransform(ti, mat)

    val sx  = kotlin.math.sqrt(mat[0]*mat[0] + mat[1]*mat[1] + mat[2]*mat[2])
    val sy  = kotlin.math.sqrt(mat[4]*mat[4] + mat[5]*mat[5] + mat[6]*mat[6])
    val sz  = kotlin.math.sqrt(mat[8]*mat[8] + mat[9]*mat[9] + mat[10]*mat[10])
    val tx  = mat[12]; val ty = mat[13]; val tz = mat[14]

    val p  = Math.toRadians(pitchDeg.toDouble()).toFloat()
    val y  = Math.toRadians(yawDeg.toDouble()).toFloat()
    val r  = Math.toRadians(rollDeg.toDouble()).toFloat()

    val cp = kotlin.math.cos(p); val sp  = kotlin.math.sin(p)
    val cy = kotlin.math.cos(y); val sy2 = kotlin.math.sin(y)
    val cr = kotlin.math.cos(r); val sr  = kotlin.math.sin(r)

    // R = Ry × Rx × Rz  (column-major)
    val r00 =  cy*cr + sy2*sp*sr;  val r01 = cp*sr;  val r02 = -sy2*cr + cy*sp*sr
    val r10 = -cy*sr + sy2*sp*cr;  val r11 = cp*cr;  val r12 =  sy2*sr + cy*sp*cr
    val r20 =  sy2*cp;             val r21 = -sp;    val r22 =  cy*cp

    mat[0] = r00*sx;  mat[1] = r10*sx;  mat[2]  = r20*sx;  mat[3]  = 0f
    mat[4] = r01*sy;  mat[5] = r11*sy;  mat[6]  = r21*sy;  mat[7]  = 0f
    mat[8] = r02*sz;  mat[9] = r12*sz;  mat[10] = r22*sz;  mat[11] = 0f
    mat[12] = tx;     mat[13] = ty;     mat[14] = tz;       mat[15] = 1f

    tm.setTransform(ti, mat)
}

// ═══════════════════════════════════════════════════════════════════════════════
//  MESH IDENTIFICATION
// ═══════════════════════════════════════════════════════════════════════════════

private fun identifyMeshType(
    instance:   ModelInstance,
    entity:     Int,
    morphCount: Int,
    eyeCount:   Int,
): ARKit.MeshType {
    try {
        val name = instance.asset?.getName(entity)?.lowercase() ?: ""
        when {
            name.contains("head")  || name.contains("face")                          -> return ARKit.MeshType.HEAD
            name.contains("teeth") || name.contains("tooth")                         -> return ARKit.MeshType.TEETH
            name.contains("eyeleft")  || name.contains("eye_l") ||
            (name.contains("eye") && name.contains("left"))                          -> return ARKit.MeshType.EYE_LEFT
            name.contains("eyeright") || name.contains("eye_r") ||
            (name.contains("eye") && name.contains("right"))                         -> return ARKit.MeshType.EYE_RIGHT
        }
    } catch (_: Exception) { }

    return when (morphCount) {
        ARKit.COUNT -> ARKit.MeshType.HEAD
        5           -> ARKit.MeshType.TEETH
        4           -> if (eyeCount == 0) ARKit.MeshType.EYE_LEFT else ARKit.MeshType.EYE_RIGHT
        else        -> ARKit.MeshType.OTHER
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  TEXTURE HELPERS
// ═══════════════════════════════════════════════════════════════════════════════

private fun loadTexture(
    ctx:    android.content.Context,
    engine: com.google.android.filament.Engine,
    path:   String,
    mipmap: Boolean = true,
): Texture? = try {
    val bmp = ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
    if (bmp == null) {
        Log.d(TAG, "Texture decode returned null: $path")
        null
    } else {
        val mipLevels = if (mipmap)
            (kotlin.math.log2(bmp.width.toFloat())).toInt().coerceAtLeast(1) + 1
        else 1

        val tex = Texture.Builder()
            .width(bmp.width).height(bmp.height).levels(mipLevels)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .usage(
                Texture.Usage.SAMPLEABLE or Texture.Usage.UPLOADABLE or
                if (mipmap) Texture.Usage.GEN_MIPMAPPABLE else 0
            )
            .build(engine)

        TextureHelper.setBitmap(engine, tex, 0, bmp)
        if (mipmap) tex.generateMipmaps(engine)
        Log.d(TAG, "Texture loaded: $path (${bmp.width}×${bmp.height}, mips=$mipLevels)")
        bmp.recycle()
        tex
    }
} catch (_: java.io.FileNotFoundException) {
    Log.d(TAG, "Texture not found in assets: $path")
    null
} catch (e: Exception) {
    Log.e(TAG, "Failed to load texture: $path", e)
    null
}

private fun buildWhiteTexture(engine: com.google.android.filament.Engine): Texture {
    val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawColor(android.graphics.Color.WHITE)
    }
    val tex = Texture.Builder()
        .width(4).height(4).levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.SRGB8_A8)
        .usage(Texture.Usage.SAMPLEABLE or Texture.Usage.UPLOADABLE)
        .build(engine)
    TextureHelper.setBitmap(engine, tex, 0, bmp)
    bmp.recycle()
    return tex
}

private fun buildHeadCompositeTexture(
    ctx:     android.content.Context,
    engine:  com.google.android.filament.Engine,
    texPath: String = HEAD_TEXTURE_1,
): Texture? = try {
    val composite = Bitmap.createBitmap(COMPOSITE_SIZE, COMPOSITE_SIZE, Bitmap.Config.ARGB_8888)
    val canvas    = Canvas(composite)

    // Базовый цвет кожи — заполняем весь composite
    canvas.drawColor(android.graphics.Color.rgb(185, 142, 96))

    try {
        val headBmp = ctx.assets.open(texPath).use { BitmapFactory.decodeStream(it) }
        if (headBmp != null) {
            val scaled = if (headBmp.width != COMPOSITE_SIZE || headBmp.height != COMPOSITE_SIZE)
                Bitmap.createScaledBitmap(headBmp, COMPOSITE_SIZE, COMPOSITE_SIZE, true)
                    .also { if (it !== headBmp) headBmp.recycle() }
            else headBmp
            canvas.drawBitmap(scaled, 0f, 0f, android.graphics.Paint())
            if (scaled !== headBmp) scaled.recycle()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Head texture overlay failed: $texPath", e)
    }

    val mipLevels = (kotlin.math.log2(COMPOSITE_SIZE.toFloat())).toInt().coerceAtLeast(1) + 1

    val tex = Texture.Builder()
        .width(COMPOSITE_SIZE).height(COMPOSITE_SIZE).levels(mipLevels)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.SRGB8_A8)
        .usage(Texture.Usage.SAMPLEABLE or Texture.Usage.UPLOADABLE or Texture.Usage.GEN_MIPMAPPABLE)
        .build(engine)

    TextureHelper.setBitmap(engine, tex, 0, composite)
    tex.generateMipmaps(engine)
    composite.recycle()

    Log.d(TAG, "Head composite texture built: ${COMPOSITE_SIZE}×${COMPOSITE_SIZE}, mips=$mipLevels")
    tex
} catch (e: Exception) {
    Log.e(TAG, "Failed to build head composite texture", e)
    null
}

// ═══════════════════════════════════════════════════════════════════════════════
//  SAMPLER BUILDERS
// ═══════════════════════════════════════════════════════════════════════════════

private fun buildDefaultSampler(): TextureSampler {
    return TextureSampler().apply {
        setMinFilter(TextureSampler.MinFilter.LINEAR)
        setMagFilter(TextureSampler.MagFilter.LINEAR)
        setWrapModeS(TextureSampler.WrapMode.CLAMP_TO_EDGE)
        setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE)
    }
}

private fun buildMipmapSampler(
    anisotropy: Float = 1f,
    wrap: TextureSampler.WrapMode = TextureSampler.WrapMode.CLAMP_TO_EDGE,
): TextureSampler {
    return TextureSampler().apply {
        setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
        setMagFilter(TextureSampler.MagFilter.LINEAR)
        setWrapModeS(wrap)
        setWrapModeT(wrap)
        setAnisotropy(anisotropy)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  MATERIAL PARAM HELPERS
// ═══════════════════════════════════════════════════════════════════════════════

private fun setParam(
    mat:     MaterialInstance,
    name:    String,
    texture: Texture,
    sampler: TextureSampler,
) {
    try {
        mat.setParameter(name, texture, sampler)
    } catch (e: Exception) {
        Log.w(TAG, "setParam($name, texture) failed: ${e.message}")
    }
}

private fun setParam(
    mat:  MaterialInstance,
    name: String,
    r: Float, g: Float, b: Float, a: Float,
) {
    try {
        mat.setParameter(name, r, g, b, a)
    } catch (e: Exception) {
        Log.w(TAG, "setParam($name, rgba) failed: ${e.message}")
    }
}

private fun setParam(
    mat:   MaterialInstance,
    name:  String,
    value: Float,
) {
    try {
        mat.setParameter(name, value)
    } catch (e: Exception) {
        Log.w(TAG, "setParam($name, float) failed: ${e.message}")
    }
}
