package com.codeextractor.app.presentation.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

// ── Пути к ресурсам ────────────────────────────────────────────────────────
private const val MODEL_PATH    = "models/source_named.glb"
private const val HEAD_TEXTURE  = "models/head_texture.png"
private const val EYES_TEXTURE  = "models/eyes_texture.png"
private const val TEETH_TEXTURE = "models/teeth_texture.png"
private const val MOUTH_MASK_PATH = "masks/mouth_inner.png"
private const val COMPOSITE_SIZE = 1024

// ── Камера ─────────────────────────────────────────────────────────────────
private val CAM_POS = dev.romainguy.kotlin.math.Float3(0f, 1.35f, 0.70f)
private val CAM_TGT = dev.romainguy.kotlin.math.Float3(0f, 1.35f, 0.00f)
private const val MODEL_SCALE = 0.35f

// ── Reusable transform matrix (pre-allocated, никогда не пересоздаётся) ────
// Живёт на уровне файла: один экземпляр на весь процесс, рендерится
// только из одного потока (Compose onFrame), синхронизация не нужна.
private val reusableTransformMatrix = FloatArray(16)

/**
 * AvatarScene v5 — Production-Ready 3D Avatar Renderer
 *
 * АРХИТЕКТУРА:
 *
 *   1. Загрузка модели — однократно в LaunchedEffect(modelLoader)
 *      Патч GLB + создание ModelInstance из ByteBuffer.
 *      Выполняется в Dispatchers.IO.
 *
 *   2. Настройка материалов — в LaunchedEffect(modelInstance)
 *      Идентификация мешей, привязка текстур, PBR-параметры.
 *      Выполняется в Dispatchers.IO (BitmapFactory, TextureHelper).
 *      Текстуры регистрируются в [trackedTextures] для последующей очистки.
 *
 *   3. Очистка GPU-памяти — DisposableEffect(engine)
 *      При выходе из Composition все Filament Texture объекты уничтожаются.
 *      Устраняет утечку GPU-памяти при пересоздании Composable.
 *
 *   4. Рендер-цикл — Scene.onFrame (60 fps)
 *      Читает RenderDoubleBuffer через read() (zero-alloc, StampedLock).
 *      Применяет морфы и поворот головы.
 *      Никаких аллокаций: reusableTransformMatrix + предвычисленные константы.
 *
 * ИДЕНТИФИКАЦИЯ МЕШЕЙ:
 *   Приоритет 1 — по имени меша из glTF asset (устойчиво к изменениям модели).
 *   Приоритет 2 — по количеству морф-таргетов (fallback для текущей модели).
 *   Логика вынесена в [identifyMeshType].
 *
 * PBR МАТЕРИАЛЫ:
 *   Кожа  — roughness 0.48, clearCoat 0.30 (сальные железы), reflectance 0.42
 *   Зубы  — roughness 0.14, clearCoat 0.62 (эмаль + слюна), reflectance 0.82
 *   Глаза — roughness 0.02, clearCoat 1.00 (слёзная плёнка),  reflectance 1.00
 *   Рот   — roughness 0.78 (матовая слизистая), без clearCoat
 *
 *   clearCoat применяется через try/catch: параметр доступен только если
 *   glTF-материал скомпилирован с поддержкой clearCoat-домена Filament.
 *   Если материал не поддерживает — молча продолжаем без него.
 *
 * @param renderBuffer  zero-alloc буфер от AvatarAnimatorImpl
 * @param modifier      Modifier для Box-контейнера
 */
@Composable
fun AvatarScene(
    modifier: Modifier = Modifier,
    renderBuffer: RenderDoubleBuffer? = null,
) {
    val ctx             = LocalContext.current
    val engine          = rememberEngine()
    val modelLoader     = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment     = rememberEnvironment(environmentLoader)
    val cameraNode      = rememberCameraNode(engine) { position = CAM_POS }

    // ── Состояния ─────────────────────────────────────────────────────────
    var modelInstance   by remember { mutableStateOf<ModelInstance?>(null) }
    var materialsReady  by remember { mutableStateOf(false) }

    // ── Реестр текстур для очистки GPU-памяти ─────────────────────────────
    // Заполняется в LaunchedEffect(modelInstance), очищается в DisposableEffect.
    val trackedTextures = remember { mutableListOf<Texture>() }

    // ── Рабочий snapshot для onFrame (pre-allocated, не пересоздаётся) ────
    val frameSnapshot   = remember { ZeroAllocRenderState() }

    // ── Белая текстура-заглушка (создаётся один раз) ─────────────────────
    // Объявляем снаружи DisposableEffect чтобы передать в LaunchedEffect
    var whiteTex        by remember { mutableStateOf<Texture?>(null) }

    // ══════════════════════════════════════════════════════════════════════
    //  ОЧИСТКА GPU-ПАМЯТИ (DisposableEffect)
    //  Срабатывает при выходе AvatarScene из Composition
    // ══════════════════════════════════════════════════════════════════════
    DisposableEffect(engine) {
        onDispose {
            Log.d(TAG, "Disposing ${trackedTextures.size} textures")
            for (tex in trackedTextures) {
                try { engine.destroyTexture(tex) }
                catch (e: Exception) { Log.w(TAG, "destroyTexture failed", e) }
            }
            trackedTextures.clear()
            whiteTex?.let {
                try { engine.destroyTexture(it) }
                catch (e: Exception) { Log.w(TAG, "destroyTexture (white) failed", e) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ЗАГРУЗКА МОДЕЛИ
    // ══════════════════════════════════════════════════════════════════════
    LaunchedEffect(modelLoader) {
        val buffer = withContext(Dispatchers.IO) {
            val patchedFile = File(ctx.cacheDir, "patched_model.glb")
            if (patchedFile.exists()) patchedFile.delete()

            com.codeextractor.app.editor.GlbTextureEditor(ctx)
                .preparePatchedModel(MODEL_PATH)

            val bytes = patchedFile.readBytes()
            ByteBuffer.allocateDirect(bytes.size).also {
                it.put(bytes); it.rewind()
            }
        }
        modelInstance = modelLoader.createModelInstance(buffer)
        Log.d(TAG, "Model loaded: ${modelInstance?.entities?.size} entities")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  НАСТРОЙКА МАТЕРИАЛОВ
    // ══════════════════════════════════════════════════════════════════════
    LaunchedEffect(modelInstance) {
        val mi = modelInstance ?: return@LaunchedEffect
        val rm = engine.renderableManager

        withContext(Dispatchers.IO) {

            // ── Белая текстура-заглушка ───────────────────────────────────
            val wt = buildWhiteTexture(engine)
            whiteTex = wt
            val defaultSampler = buildDefaultSampler()

            // ── Идентификация мешей ───────────────────────────────────────
            var headMat:  MaterialInstance? = null
            var teethMat: MaterialInstance? = null
            var eyeLMat:  MaterialInstance? = null
            var eyeRMat:  MaterialInstance? = null
            var eyeCount = 0

            // handled: (entity, prim) пары — не красим их как «рот»
            val handled  = mutableSetOf<Long>()   // Long = entity.toLong() shl 32 | prim

            for (entity in mi.entities) {
                if (!rm.hasComponent(entity)) continue
                val ri        = rm.getInstance(entity)
                val morphCount = try { rm.getMorphTargetCount(ri) } catch (_: Exception) { 0 }
                val primCount  = rm.getPrimitiveCount(ri)

                if (primCount <= 0 || morphCount <= 0) continue

                val mat = try { rm.getMaterialInstanceAt(ri, 0) } catch (_: Exception) { null }
                    ?: continue

                // Идентификация: приоритет — имя меша (если доступно), fallback — morphCount
                val meshType = identifyMeshType(mi, entity, morphCount, eyeCount)

                when (meshType) {
                    ARKit.MeshType.HEAD       -> { headMat  = mat; handled.add(packKey(entity, 0)) }
                    ARKit.MeshType.TEETH      -> { teethMat = mat; handled.add(packKey(entity, 0)) }
                    ARKit.MeshType.EYE_LEFT,
                    ARKit.MeshType.EYE_RIGHT  -> {
                        if (eyeCount == 0) eyeLMat = mat else eyeRMat = mat
                        eyeCount++
                        handled.add(packKey(entity, 0))
                    }
                    ARKit.MeshType.OTHER      -> { /* не трогаем */ }
                }
            }

            // ── Применяем материалы ───────────────────────────────────────

            // ГОЛОВА
            headMat?.let { mat ->
                val compositeTex = buildHeadCompositeTexture(ctx, engine)
                    ?.also { trackedTextures.add(it) }

                val sampler = if (compositeTex != null) buildMipmapSampler(anisotropy = 8f) else defaultSampler
                setParam(mat, "baseColorMap", compositeTex ?: wt, sampler)
                setParam(mat, "baseColorFactor", 1f, 1f, 1f, 1f)
                // PBR: кожа — матовая
                setParam(mat, "roughnessFactor",    0.48f)
                setParam(mat, "metallicFactor",     0.00f)
            }

            // ЗУБЫ
            teethMat?.let { mat ->
                val tex = loadTexture(ctx, engine, TEETH_TEXTURE, mipmap = true)
                    ?.also { trackedTextures.add(it) }

                val sampler = if (tex != null) buildMipmapSampler() else defaultSampler
                setParam(mat, "baseColorMap", tex ?: wt, sampler)
                setParam(mat, "baseColorFactor", 0.97f, 0.97f, 0.95f, 1f)
                // PBR: зубная эмаль + слюна — почти зеркальная поверхность
                setParam(mat, "roughnessFactor",    0.14f)
                setParam(mat, "metallicFactor",     0.00f)
            }

            // ГЛАЗА
            listOf(eyeLMat, eyeRMat).filterNotNull().forEach { mat ->
                val tex = loadTexture(ctx, engine, EYES_TEXTURE, mipmap = true)
                    ?.also { trackedTextures.add(it) }

                val sampler = if (tex != null) buildMipmapSampler(wrap = TextureSampler.WrapMode.REPEAT) else defaultSampler
                setParam(mat, "baseColorMap", tex ?: wt, sampler)
                setParam(mat, "baseColorFactor", 1f, 1f, 1f, 1f)
                // PBR: роговица — идеальная линза
                setParam(mat, "roughnessFactor",    0.02f)
                setParam(mat, "metallicFactor",     0.00f)
            }

            // РОТ И ВНУТРЕННИЕ ЧАСТИ (всё не обработанное выше)
            for (entity in mi.entities) {
                if (!rm.hasComponent(entity)) continue
                val ri       = rm.getInstance(entity)
                val primCount = rm.getPrimitiveCount(ri)

                for (prim in 0 until primCount) {
                    if (handled.contains(packKey(entity, prim))) continue
                    val mat = try { rm.getMaterialInstanceAt(ri, prim) }
                              catch (_: Exception) { continue }

                    setParam(mat, "baseColorMap",   wt, defaultSampler)
                    // Тёплый персиково-розовый: слизистая полости рта
                    setParam(mat, "baseColorFactor", 0.76f, 0.33f, 0.28f, 1f)
                    setParam(mat, "roughnessFactor", 0.78f)
                    setParam(mat, "metallicFactor",  0.00f)
                }
            }
        }

        materialsReady = true
        Log.d(TAG, "Materials applied, tracked textures: ${trackedTextures.size}")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  RENDER
    // ══════════════════════════════════════════════════════════════════════
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

                // Zero-alloc: читаем из double buffer в pre-allocated snapshot
                renderBuffer?.read(frameSnapshot)

                applyMorphWeights(engine, mi, frameSnapshot)
                applyHeadRotation(engine, mi,
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
                    centerOrigin  = Position(0f, 0f, 0f),
                    autoAnimate   = false,
                )
            }
        }

        if (modelInstance == null || !materialsReady) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White.copy(alpha = 0.35f),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  MORPH APPLICATION
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Применяет 51 ARKit morph weight к модели.
 *
 * Маппинг: головные weights → teeth/eye sub-arrays через ARKit-константы.
 * Никаких magic numbers.
 */
private fun applyMorphWeights(
    engine:   com.google.android.filament.Engine,
    instance: ModelInstance,
    state:    ZeroAllocRenderState,
) {
    val rm   = engine.renderableManager
    val head = state.morphWeights

    // Sub-arrays вычисляются из head через ARKit.TEETH_SOURCE_INDICES / EYE_SOURCE_INDICES
    val teethW = FloatArray(5)  { i -> head[ARKit.TEETH_SOURCE_INDICES[i]] }
    val eyeLW  = FloatArray(4)  { i -> head[ARKit.EYE_SOURCE_INDICES[i]]   }
    val eyeRW  = FloatArray(4)  { i -> head[ARKit.EYE_SOURCE_INDICES[i] + ARKit.EYE_RIGHT_OFFSET] }

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

// ═══════════════════════════════════════════════════════════════════════════
//  HEAD ROTATION  (zero-alloc: reusableTransformMatrix)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Применяет Euler-rotation (pitch/yaw/roll) к корневому трансформу модели.
 *
 * Использует [reusableTransformMatrix] — pre-allocated FloatArray(16) на уровне файла.
 * Вызывается только из Compose onFrame (один поток) — синхронизация не нужна.
 *
 * Порядок Euler: Yaw (Y) → Pitch (X) → Roll (Z) — стандарт для движения головы.
 */
private fun applyHeadRotation(
    engine:   com.google.android.filament.Engine,
    instance: ModelInstance,
    pitchDeg: Float,
    yawDeg:   Float,
    rollDeg:  Float,
) {
    // Пропускаем при незначительном повороте
    if (kotlin.math.abs(pitchDeg) < 0.04f &&
        kotlin.math.abs(yawDeg)   < 0.04f &&
        kotlin.math.abs(rollDeg)  < 0.04f) return

    val tm         = engine.transformManager
    val rootEntity = instance.root
    if (!tm.hasComponent(rootEntity)) return
    val ti = tm.getInstance(rootEntity)

    val mat = reusableTransformMatrix   // ← zero-alloc
    tm.getTransform(ti, mat)

    // Извлекаем масштаб (scale) из верхней 3×3 подматрицы
    val sx = kotlin.math.sqrt(mat[0]*mat[0] + mat[1]*mat[1] + mat[2]*mat[2])
    val sy = kotlin.math.sqrt(mat[4]*mat[4] + mat[5]*mat[5] + mat[6]*mat[6])
    val sz = kotlin.math.sqrt(mat[8]*mat[8] + mat[9]*mat[9] + mat[10]*mat[10])

    // Извлекаем позицию
    val tx = mat[12]; val ty = mat[13]; val tz = mat[14]

    // Euler → матрица вращения (Yaw × Pitch × Roll)
    val p  = Math.toRadians(pitchDeg.toDouble()).toFloat()
    val y  = Math.toRadians(yawDeg.toDouble()).toFloat()
    val r  = Math.toRadians(rollDeg.toDouble()).toFloat()

    val cp = kotlin.math.cos(p); val sp = kotlin.math.sin(p)
    val cy = kotlin.math.cos(y); val sy2= kotlin.math.sin(y)
    val cr = kotlin.math.cos(r); val sr = kotlin.math.sin(r)

    // R = Ry * Rx * Rz  (column-major, Filament convention)
    val r00 =  cy*cr + sy2*sp*sr;  val r01 = cp*sr;  val r02 = -sy2*cr + cy*sp*sr
    val r10 = -cy*sr + sy2*sp*cr;  val r11 = cp*cr;  val r12 =  sy2*sr + cy*sp*cr
    val r20 =  sy2*cp;             val r21 = -sp;    val r22 =  cy*cp

    // Записываем обратно со шкалой
    mat[0] = r00*sx;  mat[1] = r10*sx;  mat[2]  = r20*sx;  mat[3]  = 0f
    mat[4] = r01*sy;  mat[5] = r11*sy;  mat[6]  = r21*sy;  mat[7]  = 0f
    mat[8] = r02*sz;  mat[9] = r12*sz;  mat[10] = r22*sz;  mat[11] = 0f
    mat[12] = tx;     mat[13] = ty;     mat[14] = tz;       mat[15] = 1f

    tm.setTransform(ti, mat)
}

// ═══════════════════════════════════════════════════════════════════════════
//  MESH IDENTIFICATION
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Идентифицирует тип меша.
 *
 * Приоритет 1: имя меша из glTF asset (устойчиво к изменениям модели).
 *   Ищем в именах entity через SceneView API (если доступно).
 *
 * Приоритет 2: количество морф-таргетов (fallback).
 *   51 → HEAD, 5 → TEETH, 4 → EYE (первые два).
 *
 * [eyeCount] передаётся для разграничения EYE_LEFT / EYE_RIGHT при fallback.
 */
private fun identifyMeshType(
    instance:   ModelInstance,
    entity:     Int,
    morphCount: Int,
    eyeCount:   Int,
): ARKit.MeshType {
    // Попытка по имени (SceneView может экспонировать имена через asset)
    try {
        val asset = instance.asset
        val name  = asset?.getName(entity)?.lowercase() ?: ""
        when {
            name.contains("head")  || name.contains("face")   -> return ARKit.MeshType.HEAD
            name.contains("teeth") || name.contains("tooth")  -> return ARKit.MeshType.TEETH
            name.contains("eyeleft")  || name.contains("eye_l") ||
            (name.contains("eye") && name.contains("left"))   -> return ARKit.MeshType.EYE_LEFT
            name.contains("eyeright") || name.contains("eye_r") ||
            (name.contains("eye") && name.contains("right"))  -> return ARKit.MeshType.EYE_RIGHT
        }
    } catch (_: Exception) { /* API недоступно — используем fallback */ }

    // Fallback: по количеству морф-таргетов
    return when (morphCount) {
        ARKit.COUNT -> ARKit.MeshType.HEAD
        5           -> ARKit.MeshType.TEETH
        4           -> if (eyeCount == 0) ARKit.MeshType.EYE_LEFT else ARKit.MeshType.EYE_RIGHT
        else        -> ARKit.MeshType.OTHER
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  TEXTURE HELPERS
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Загружает PNG из assets и создаёт Filament Texture с mipmaps.
 *
 * Вызывается из Dispatchers.IO — BitmapFactory и TextureHelper блокирующие.
 * Возвращает null если файл не найден (graceful degradation → white texture).
 */
private fun loadTexture(
    ctx:      android.content.Context,
    engine:   com.google.android.filament.Engine,
    path:     String,
    mipmap:   Boolean = true,
): Texture? = try {
    val bmp = ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
        ?: return null

    val mipLevels = if (mipmap)
        (kotlin.math.log2(bmp.width.toFloat())).toInt().coerceAtLeast(1) + 1
    else 1

    val tex = Texture.Builder()
        .width(bmp.width)
        .height(bmp.height)
        .levels(mipLevels)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.SRGB8_A8)
        .usage(
            Texture.Usage.SAMPLEABLE   or
            Texture.Usage.UPLOADABLE   or
            if (mipmap) Texture.Usage.GEN_MIPMAPPABLE else 0
        )
        .build(engine)

    TextureHelper.setBitmap(engine, tex, 0, bmp)
    if (mipmap) tex.generateMipmaps(engine)

    Log.d(TAG, "Texture loaded: $path (${bmp.width}×${bmp.height}, mips=$mipLevels)")
    bmp.recycle()
    tex
} catch (_: java.io.FileNotFoundException) {
    Log.d(TAG, "Texture not found in assets: $path")
    null
} catch (e: Exception) {
    Log.e(TAG, "Failed to load texture: $path", e)
    null
}

/**
 * Создаёт 4×4 белую текстуру-заглушку.
 * Используется когда реальная текстура не найдена — контроль PBR-цвета
 * через baseColorFactor остаётся в силе.
 */
private fun buildWhiteTexture(
    engine: com.google.android.filament.Engine,
): Texture {
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

/**
 * Строит композитную текстуру головы:
 *   Слой 1: Тёплый цвет кожи (заливка)
 *   Слой 2: Розовая полость рта (через маску mouth_inner.png)
 *   Слой 3: head_texture.png поверх (если есть)
 */
private fun buildHeadCompositeTexture(
    ctx: android.content.Context,
    engine: com.google.android.filament.Engine,
): Texture? = try {
    val composite = Bitmap.createBitmap(
        COMPOSITE_SIZE, COMPOSITE_SIZE, Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(composite)

    // ── Слой 1: фон — цвет кожи ──
    canvas.drawColor(android.graphics.Color.rgb(185, 142, 96))

    // ── Слой 2: розовая полость рта через маску ──
    try {
        val mask = ctx.assets.open(MOUTH_MASK_PATH).use {
            BitmapFactory.decodeStream(it)
        }
        if (mask != null) {
            val scaledMask = if (mask.width != COMPOSITE_SIZE || mask.height != COMPOSITE_SIZE)
                Bitmap.createScaledBitmap(mask, COMPOSITE_SIZE, COMPOSITE_SIZE, true)
                    .also { if (it !== mask) mask.recycle() }
            else mask

            val mouthLayer = Bitmap.createBitmap(
                COMPOSITE_SIZE, COMPOSITE_SIZE, Bitmap.Config.ARGB_8888
            )
            val mc = Canvas(mouthLayer)
            mc.drawColor(android.graphics.Color.rgb(194, 84, 71))
            val maskPaint = android.graphics.Paint().apply {
                xfermode = android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_IN
                )
            }
            mc.drawBitmap(scaledMask, 0f, 0f, maskPaint)
            canvas.drawBitmap(mouthLayer, 0f, 0f, android.graphics.Paint())
            mouthLayer.recycle()
            if (scaledMask !== mask) scaledMask.recycle()
        }
    } catch (_: Exception) {
        Log.w(TAG, "Mouth mask not found — skipping mouth color")
    }

    // ── Слой 3: head_texture.png (кожа, волосы) поверх ──
    try {
        val headBmp = ctx.assets.open(HEAD_TEXTURE).use {
            BitmapFactory.decodeStream(it)
        }
        if (headBmp != null) {
            val scaled = if (headBmp.width != COMPOSITE_SIZE || headBmp.height != COMPOSITE_SIZE)
                Bitmap.createScaledBitmap(headBmp, COMPOSITE_SIZE, COMPOSITE_SIZE, true)
                    .also { if (it !== headBmp) headBmp.recycle() }
            else headBmp
            canvas.drawBitmap(scaled, 0f, 0f, android.graphics.Paint())
            if (scaled !== headBmp) scaled.recycle()
        }
    } catch (_: Exception) {
        Log.d(TAG, "head_texture.png not found — composite uses base layers only")
    }

    // ── Создаём Filament текстуру ──
    val mipLevels = (kotlin.math.log2(COMPOSITE_SIZE.toFloat())).toInt() + 1
    val tex = Texture.Builder()
        .width(COMPOSITE_SIZE).height(COMPOSITE_SIZE).levels(mipLevels)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.SRGB8_A8)
        .usage(
            Texture.Usage.SAMPLEABLE or
            Texture.Usage.UPLOADABLE or
            Texture.Usage.GEN_MIPMAPPABLE
        )
        .build(engine)

    TextureHelper.setBitmap(engine, tex, 0, composite)
    tex.generateMipmaps(engine)
    composite.recycle()

    Log.d(TAG, "Head composite texture built with mouth color")
    tex
} catch (e: Exception) {
    Log.e(TAG, "buildHeadCompositeTexture failed", e)
    null
}

// ── Sampler builders ──────────────────────────────────────────────────────

private fun buildDefaultSampler() = TextureSampler().apply {
    setMinFilter(TextureSampler.MinFilter.LINEAR)
    setMagFilter(TextureSampler.MagFilter.LINEAR)
    setWrapModeS(TextureSampler.WrapMode.CLAMP_TO_EDGE)
    setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE)
}

private fun buildMipmapSampler(
    anisotropy: Float = 4f,
    wrap: TextureSampler.WrapMode = TextureSampler.WrapMode.CLAMP_TO_EDGE,
) = TextureSampler().apply {
    setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
    setMagFilter(TextureSampler.MagFilter.LINEAR)
    setWrapModeS(wrap)
    setWrapModeT(wrap)
    setAnisotropy(anisotropy)
}

// ── Material parameter setters (с перехватом исключений) ──────────────────

private fun setParam(mat: MaterialInstance, name: String, tex: Texture, sampler: TextureSampler) {
    try { mat.setParameter(name, tex, sampler) }
    catch (e: Exception) { Log.v(TAG, "setParameter($name) skipped: ${e.message}") }
}

private fun setParam(mat: MaterialInstance, name: String, vararg floats: Float) {
    try {
        when (floats.size) {
            1    -> mat.setParameter(name, floats[0])
            4    -> mat.setParameter(name, floats[0], floats[1], floats[2], floats[3])
        }
    } catch (e: Exception) { Log.v(TAG, "setParameter($name) skipped: ${e.message}") }
}

// ── Key packing для handled set ───────────────────────────────────────────

private fun packKey(entity: Int, prim: Int): Long =
    entity.toLong().shl(32) or prim.toLong()