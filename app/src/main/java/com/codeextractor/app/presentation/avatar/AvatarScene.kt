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

// ── Ресурсы аватара 1 (мужской) ───────────────────────────────────────────
private const val MODEL_PATH_1    = "models/test.glb"
private const val HEAD_TEXTURE_1  = "models/head_texture.png"
private const val EYES_TEXTURE_1  = "models/eyes_texture.png"
private const val TEETH_TEXTURE_1 = "models/teeth_texture.png"

// ── Ресурсы аватара 2 (женский) ───────────────────────────────────────────
private const val MODEL_PATH_2    = "models/test2.glb"
private const val HEAD_TEXTURE_2  = "models/head_texture2.png"
private const val EYES_TEXTURE_2  = "models/eyes_texture2.png"
private const val TEETH_TEXTURE_2 = "models/teeth_texture2.png"

private const val MOUTH_MASK_PATH = "masks/mouth_inner.png"
private const val COMPOSITE_SIZE  = 1024

// ── Камера ─────────────────────────────────────────────────────────────────
private val CAM_POS = dev.romainguy.kotlin.math.Float3(0f, 1.35f, 0.70f)
private val CAM_TGT = dev.romainguy.kotlin.math.Float3(0f, 1.35f, 0.00f)
private const val MODEL_SCALE = 0.35f

// ══════════════════════════════════════════════════════════════════════════
//  PRE-ALLOCATED ARRAYS (zero-alloc в onFrame, один экземпляр на процесс)
//  Все массивы используются строго из одного потока (Compose onFrame).
// ══════════════════════════════════════════════════════════════════════════

/** Матрица трансформа для поворота головы (root entity). */
private val reusableTransformMatrix = FloatArray(16)

/**
 * REST-трансформы глаз — снимаются один раз после загрузки модели,
 * когда голова ещё не повёрнута (дефолтная поза = смотрит на камеру).
 * Используются в [applyEyeGaze] как эталон «взгляд на клиента».
 */
private val eyeLeftRestTransform  = FloatArray(16)
private val eyeRightRestTransform = FloatArray(16)

/** R⁻¹ = Rᵀ — инверсия матрицы поворота головы, вычисляется каждый кадр. */
private val gazeInvRotation = FloatArray(16)

/** Результирующий трансформ глаза: R⁻¹ × rest_transform. */
private val gazeResult = FloatArray(16)

/**
 * Флаг: rest-трансформы захвачены, gaze-коррекция активна.
 * Volatile: пишется из LaunchedEffect (Main), читается из onFrame (Main).
 */
@Volatile private var eyeRestCaptured = false

/**
 * Entity ID левого и правого глаз.
 * 0 = не захвачен. Volatile достаточно: запись и чтение на Main dispatcher.
 */
@Volatile private var eyeLeftEntityId:  Int = 0
@Volatile private var eyeRightEntityId: Int = 0

// ══════════════════════════════════════════════════════════════════════════

/**
 * AvatarScene v7 — Production-Ready 3D Avatar Renderer
 *
 * АРХИТЕКТУРА:
 *
 *   1. Загрузка модели — LaunchedEffect(modelLoader, avatarIndex)
 *      Патч GLB → ByteBuffer → ModelInstance. Dispatchers.IO.
 *
 *   2. Настройка материалов — LaunchedEffect(modelInstance, avatarIndex)
 *      Идентификация мешей, текстуры, PBR-параметры. Dispatchers.IO.
 *      После withContext — захват eye rest-трансформов на Main dispatcher,
 *      пока голова ещё в дефолтной позе (= смотрит на камеру).
 *
 *   3. Рендер-цикл — Scene.onFrame (60 fps), zero-alloc:
 *      applyMorphWeights  — ARKit blend-shapes (анимация сохраняется полностью).
 *      applyHeadRotation  — поворот root entity на (pitch, yaw, roll).
 *      applyEyeGaze       — коррекция взгляда (см. ниже).
 *
 * ПЕРЕКЛЮЧЕНИЕ АВАТАРОВ:
 *   Кнопка ♀/♂ в правом нижнем углу меняет avatarIndex (1 или 2).
 *   Оба LaunchedEffect имеют avatarIndex в ключах → перезапускаются,
 *   сбрасывают состояние и загружают ресурсы соответствующего аватара.
 *   Кэш-файлы раздельные (patched_model_1.glb / patched_model_2.glb).
 *
 * GAZE CORRECTION — математика:
 *   Проблема: root повёрнут на матрицу R → все children (в т.ч. глаза)
 *             поворачиваются вместе → взгляд уходит от камеры.
 *   Решение:  eye_local_new = R⁻¹ × eye_local_rest
 *   Доказательство:
 *     eye_world = R × eye_local_new = R × (R⁻¹ × rest) = rest   ← всегда на камеру ✓
 *   R⁻¹ = Rᵀ (транспонирование ортогональной матрицы вращения).
 *
 *   Анимация СОХРАНЯЕТСЯ:
 *     Морф-таргеты глаз (моргание, прищур) живут в blend-shapes и
 *     не зависят от transform entity → работают как прежде. ✓
 *
 * PBR МАТЕРИАЛЫ:
 *   Голова  — roughness 0.48 (матовая кожа)
 *   Зубы    — roughness 0.35 с текстурой / 0.85 (тёмно-розовая слизистая) без
 *   Глаза   — roughness 0.02 (роговица)
 *
 * @param renderBuffer  zero-alloc буфер от AvatarAnimatorImpl
 * @param modifier      Modifier для Box-контейнера
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

    // ── Текущий аватар: 1 = мужской, 2 = женский ─────────────────────────
    var avatarIndex    by remember { mutableStateOf(1) }

    var modelInstance  by remember { mutableStateOf<ModelInstance?>(null) }
    var materialsReady by remember { mutableStateOf(false) }

    val trackedTextures = remember { mutableListOf<Texture>() }
    val frameSnapshot   = remember { ZeroAllocRenderState() }
    var whiteTex        by remember { mutableStateOf<Texture?>(null) }

    // Хелперы путей в зависимости от выбранного аватара
    fun modelPath() = if (avatarIndex == 1) MODEL_PATH_1    else MODEL_PATH_2
    fun headTex()   = if (avatarIndex == 1) HEAD_TEXTURE_1  else HEAD_TEXTURE_2
    fun eyesTex()   = if (avatarIndex == 1) EYES_TEXTURE_1  else EYES_TEXTURE_2
    fun teethTex()  = if (avatarIndex == 1) TEETH_TEXTURE_1 else TEETH_TEXTURE_2

    // ── Сброс gaze-состояния при выходе из Composition ───────────────────
    DisposableEffect(Unit) {
        onDispose {
            eyeRestCaptured  = false
            eyeLeftEntityId  = 0
            eyeRightEntityId = 0
        }
    }

    // ── Очистка GPU-памяти ────────────────────────────────────────────────
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
    //  Ключ: modelLoader + avatarIndex — перезапускается при смене аватара.
    // ══════════════════════════════════════════════════════════════════════
    LaunchedEffect(modelLoader, avatarIndex) {
        // Сброс состояния перед загрузкой нового аватара
        modelInstance    = null
        materialsReady   = false
        eyeRestCaptured  = false
        eyeLeftEntityId  = 0
        eyeRightEntityId = 0

        val buffer = withContext(Dispatchers.IO) {
            // GlbTextureEditor всегда пишет в patched_model.glb
            val editorOutput = File(ctx.cacheDir, "patched_model.glb")
            // Кэшированный файл для конкретного аватара
            val patchedFile  = File(ctx.cacheDir, "patched_model_$avatarIndex.glb")

            if (patchedFile.exists()) patchedFile.delete()

            com.codeextractor.app.editor.GlbTextureEditor(ctx)
                .preparePatchedModel(modelPath())

            // Переименовываем выход пайпа в файл с индексом аватара
            editorOutput.renameTo(patchedFile)

            val bytes = patchedFile.readBytes()
            ByteBuffer.allocateDirect(bytes.size).also {
                it.put(bytes); it.rewind()
            }
        }
        modelInstance = modelLoader.createModelInstance(buffer)
        Log.d(TAG, "Model loaded [avatar=$avatarIndex]: ${modelInstance?.entities?.size} entities")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  НАСТРОЙКА МАТЕРИАЛОВ + ЗАХВАТ EYE REST-ТРАНСФОРМОВ
    //  Ключ: modelInstance + avatarIndex — перезапускается при смене аватара.
    // ══════════════════════════════════════════════════════════════════════
    LaunchedEffect(modelInstance, avatarIndex) {
        val mi = modelInstance ?: return@LaunchedEffect
        val rm = engine.renderableManager

        var capturedEyeLeft:  Int? = null
        var capturedEyeRight: Int? = null

        withContext(Dispatchers.IO) {

            // Освобождаем текстуры предыдущего аватара перед созданием новых
            for (tex in trackedTextures) {
                try { engine.destroyTexture(tex) }
                catch (e: Exception) { Log.w(TAG, "destroyTexture (swap) failed", e) }
            }
            trackedTextures.clear()

            val wt             = buildWhiteTexture(engine)
            whiteTex           = wt
            val defaultSampler = buildDefaultSampler()

            var headMat:  MaterialInstance? = null
            var teethMat: MaterialInstance? = null
            var eyeLMat:  MaterialInstance? = null
            var eyeRMat:  MaterialInstance? = null
            var eyeCount = 0
            val handled  = mutableSetOf<Long>()

            for (entity in mi.entities) {
                if (!rm.hasComponent(entity)) continue
                val ri         = rm.getInstance(entity)
                val morphCount = try { rm.getMorphTargetCount(ri) } catch (_: Exception) { 0 }
                val primCount  = rm.getPrimitiveCount(ri)
                if (primCount <= 0 || morphCount <= 0) continue

                val mat = try { rm.getMaterialInstanceAt(ri, 0) } catch (_: Exception) { null }
                    ?: continue

                val meshType = identifyMeshType(mi, entity, morphCount, eyeCount)

                when (meshType) {
                    ARKit.MeshType.HEAD  -> {
                        headMat = mat
                        handled.add(packKey(entity, 0))
                    }
                    ARKit.MeshType.TEETH -> {
                        teethMat = mat
                        handled.add(packKey(entity, 0))
                    }
                    ARKit.MeshType.EYE_LEFT,
                    ARKit.MeshType.EYE_RIGHT -> {
                        if (eyeCount == 0) {
                            eyeLMat         = mat
                            capturedEyeLeft = entity
                        } else {
                            eyeRMat          = mat
                            capturedEyeRight = entity
                        }
                        eyeCount++
                        handled.add(packKey(entity, 0))
                    }
                    ARKit.MeshType.OTHER -> { /* не трогаем */ }
                }
            }

            // ── ГОЛОВА ────────────────────────────────────────────────────
            headMat?.let { mat ->
                val compositeTex = buildHeadCompositeTexture(ctx, engine, headTex())
                    ?.also { trackedTextures.add(it) }
                val sampler = if (compositeTex != null) buildMipmapSampler(anisotropy = 8f)
                              else defaultSampler
                setParam(mat, "baseColorMap",    compositeTex ?: wt, sampler)
                setParam(mat, "baseColorFactor", 1f, 1f, 1f, 1f)
                setParam(mat, "roughnessFactor", 0.48f)
                setParam(mat, "metallicFactor",  0.00f)
            }

            // ── ЗУБЫ / ВНУТРЕННОСТЬ РТА ──────────────────────────────────
            teethMat?.let { mat ->
                val tex = loadTexture(ctx, engine, teethTex(), mipmap = true)
                    ?.also { trackedTextures.add(it) }

                if (tex != null) {
                    setParam(mat, "baseColorMap",    tex, buildMipmapSampler())
                    setParam(mat, "baseColorFactor", 0.97f, 0.97f, 0.95f, 1f)
                    setParam(mat, "roughnessFactor", 0.35f)
                    setParam(mat, "metallicFactor",  0.00f)
                    Log.d(TAG, "Teeth: texture mode")
                } else {
                    setParam(mat, "baseColorMap",    wt, defaultSampler)
                    setParam(mat, "baseColorFactor", 0.55f, 0.22f, 0.20f, 1f)
                    setParam(mat, "roughnessFactor", 0.85f)
                    setParam(mat, "metallicFactor",  0.00f)
                    Log.d(TAG, "Teeth: no texture → mouth interior fallback")
                }
            }

            // ── ГЛАЗА ─────────────────────────────────────────────────────
            listOf(eyeLMat, eyeRMat).filterNotNull().forEach { mat ->
                val tex = loadTexture(ctx, engine, eyesTex(), mipmap = true)
                    ?.also { trackedTextures.add(it) }
                val sampler = if (tex != null)
                    buildMipmapSampler(wrap = TextureSampler.WrapMode.REPEAT)
                else defaultSampler
                setParam(mat, "baseColorMap",    tex ?: wt, sampler)
                setParam(mat, "baseColorFactor", 1f, 1f, 1f, 1f)
                setParam(mat, "roughnessFactor", 0.02f)
                setParam(mat, "metallicFactor",  0.00f)
            }
        }
        // ── withContext вернулся: мы снова на Main dispatcher ─────────────

        // ── ЗАХВАТ EYE REST-ТРАНСФОРМОВ ───────────────────────────────────
        val tm = engine.transformManager

        capturedEyeLeft?.let { entity ->
            eyeLeftEntityId = entity
            if (tm.hasComponent(entity)) {
                tm.getTransform(tm.getInstance(entity), eyeLeftRestTransform)
                Log.d(TAG, "Eye left  rest transform captured (entity=$entity)")
            }
        }
        capturedEyeRight?.let { entity ->
            eyeRightEntityId = entity
            if (tm.hasComponent(entity)) {
                tm.getTransform(tm.getInstance(entity), eyeRightRestTransform)
                Log.d(TAG, "Eye right rest transform captured (entity=$entity)")
            }
        }

        eyeRestCaptured = true
        materialsReady  = true
        Log.d(TAG, "Materials ready [avatar=$avatarIndex]. Gaze ON. Textures: ${trackedTextures.size}")
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

                renderBuffer?.read(frameSnapshot)

                // 1. Blend-shapes: губы, моргание, прищур, etc.
                applyMorphWeights(engine, mi, frameSnapshot)

                // 2. Поворот головы
                applyHeadRotation(
                    engine, mi,
                    frameSnapshot.headPitch,
                    frameSnapshot.headYaw,
                    frameSnapshot.headRoll,
                )

                // 3. Компенсируем поворот у глаз → взгляд всегда на камеру
                applyEyeGaze(
                    engine,
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

        // ── Индикатор загрузки ────────────────────────────────────────────
        if (modelInstance == null || !materialsReady) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White.copy(alpha = 0.35f),
            )
        }

        // ── Кнопка переключения аватара ───────────────────────────────────
        IconButton(
            onClick = {
                avatarIndex = if (avatarIndex == 1) 2 else 1
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(48.dp)
                .background(
                    color  = Color.White.copy(alpha = 0.15f),
                    shape  = CircleShape,
                ),
        ) {
            Text(
                text     = if (avatarIndex == 1) "♀" else "♂",
                color    = Color.White,
                fontSize = TextUnit(22f, TextUnitType.Sp),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  MORPH APPLICATION
// ═══════════════════════════════════════════════════════════════════════════

private fun applyMorphWeights(
    engine:   com.google.android.filament.Engine,
    instance: ModelInstance,
    state:    ZeroAllocRenderState,
) {
    val rm   = engine.renderableManager
    val head = state.morphWeights

    val teethW = FloatArray(5) { i -> head[ARKit.TEETH_SOURCE_INDICES[i]] }
    val eyeLW  = FloatArray(4) { i -> head[ARKit.EYE_SOURCE_INDICES[i]]   }
    val eyeRW  = FloatArray(4) { i -> head[ARKit.EYE_SOURCE_INDICES[i] + ARKit.EYE_RIGHT_OFFSET] }

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
//  HEAD ROTATION  (zero-alloc)
// ═══════════════════════════════════════════════════════════════════════════

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

    val sx = kotlin.math.sqrt(mat[0]*mat[0] + mat[1]*mat[1] + mat[2]*mat[2])
    val sy = kotlin.math.sqrt(mat[4]*mat[4] + mat[5]*mat[5] + mat[6]*mat[6])
    val sz = kotlin.math.sqrt(mat[8]*mat[8] + mat[9]*mat[9] + mat[10]*mat[10])
    val tx = mat[12]; val ty = mat[13]; val tz = mat[14]

    val p = Math.toRadians(pitchDeg.toDouble()).toFloat()
    val y = Math.toRadians(yawDeg.toDouble()).toFloat()
    val r = Math.toRadians(rollDeg.toDouble()).toFloat()

    val cp = kotlin.math.cos(p); val sp = kotlin.math.sin(p)
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

// ═══════════════════════════════════════════════════════════════════════════
//  EYE GAZE CORRECTION  (zero-alloc)
//
//  Математика (подробнее в KDoc класса):
//    eye_local_new = R⁻¹ × eye_local_rest
//    eye_world     = R × eye_local_new = rest   ← всегда на камеру
//
//  R⁻¹ = Rᵀ в column-major:
//    Исходная column-major запись: mat[col*4 + row] = R[row, col]
//    Транспонирование:             mat_T[col*4 + row] = R[col, row]
// ═══════════════════════════════════════════════════════════════════════════

private fun applyEyeGaze(
    engine:   com.google.android.filament.Engine,
    pitchDeg: Float,
    yawDeg:   Float,
    rollDeg:  Float,
) {
    if (!eyeRestCaptured) return
    if (eyeLeftEntityId == 0 && eyeRightEntityId == 0) return

    val p = Math.toRadians(pitchDeg.toDouble()).toFloat()
    val y = Math.toRadians(yawDeg.toDouble()).toFloat()
    val r = Math.toRadians(rollDeg.toDouble()).toFloat()

    val cp = kotlin.math.cos(p); val sp = kotlin.math.sin(p)
    val cy = kotlin.math.cos(y); val sy = kotlin.math.sin(y)
    val cr = kotlin.math.cos(r); val sr = kotlin.math.sin(r)

    val r00 =  cy*cr + sy*sp*sr;  val r01 = cp*sr;  val r02 = -sy*cr + cy*sp*sr
    val r10 = -cy*sr + sy*sp*cr;  val r11 = cp*cr;  val r12 =  sy*sr + cy*sp*cr
    val r20 =  sy*cp;             val r21 = -sp;    val r22 =  cy*cp

    // R⁻¹ = Rᵀ (column-major transpose)
    val inv = gazeInvRotation
    inv[0]  = r00;  inv[1]  = r01;  inv[2]  = r02;  inv[3]  = 0f
    inv[4]  = r10;  inv[5]  = r11;  inv[6]  = r12;  inv[7]  = 0f
    inv[8]  = r20;  inv[9]  = r21;  inv[10] = r22;  inv[11] = 0f
    inv[12] = 0f;   inv[13] = 0f;   inv[14] = 0f;   inv[15] = 1f

    val tm = engine.transformManager

    applyGazeToEye(tm, eyeLeftEntityId,  eyeLeftRestTransform)
    applyGazeToEye(tm, eyeRightEntityId, eyeRightRestTransform)
}

/**
 * Вычисляет и применяет скорректированный трансформ для одного глаза.
 *
 * ТОЛЬКО верхний левый блок 3×3 (ориентация) умножается на R⁻¹.
 * Колонка трансляции (позиция глаза в локальном пространстве головы)
 * берётся из rest без изменений — именно это не давало глазу «плавать».
 *
 *   out_rot  = inv_rot × rest_rot   ← 3×3 умножение
 *   out_pos  = rest_pos             ← без изменений (глаз остаётся в глазнице!)
 */
private fun applyGazeToEye(
    tm:       com.google.android.filament.TransformManager,
    entityId: Int,
    rest:     FloatArray,
) {
    if (entityId == 0 || !tm.hasComponent(entityId)) return
    val ti  = tm.getInstance(entityId)
    val inv = gazeInvRotation
    val out = gazeResult

    // ── Умножаем только 3×3 блок ориентации ──────────────────────────────
    // col-major: mat[col*4 + row], ограничиваем k до 0..2 (чистое вращение)
    for (col in 0..2) {
        for (row in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += inv[k * 4 + row] * rest[col * 4 + k]
            out[col * 4 + row] = sum
        }
        out[col * 4 + 3] = 0f   // w-компонент каждой колонки вращения = 0
    }

    // ── Позицию глаза берём из rest — глаз НЕ смещается из глазницы ──────
    out[12] = rest[12]
    out[13] = rest[13]
    out[14] = rest[14]
    out[15] = 1f

    tm.setTransform(ti, out)
}

// ═══════════════════════════════════════════════════════════════════════════
//  MESH IDENTIFICATION
// ═══════════════════════════════════════════════════════════════════════════

private fun identifyMeshType(
    instance:   ModelInstance,
    entity:     Int,
    morphCount: Int,
    eyeCount:   Int,
): ARKit.MeshType {
    try {
        val asset = instance.asset
        val name  = asset?.getName(entity)?.lowercase() ?: ""
        when {
            name.contains("head")  || name.contains("face")     -> return ARKit.MeshType.HEAD
            name.contains("teeth") || name.contains("tooth")    -> return ARKit.MeshType.TEETH
            name.contains("eyeleft")  || name.contains("eye_l") ||
            (name.contains("eye") && name.contains("left"))     -> return ARKit.MeshType.EYE_LEFT
            name.contains("eyeright") || name.contains("eye_r") ||
            (name.contains("eye") && name.contains("right"))    -> return ARKit.MeshType.EYE_RIGHT
        }
    } catch (_: Exception) { }

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

private fun loadTexture(
    ctx:    android.content.Context,
    engine: com.google.android.filament.Engine,
    path:   String,
    mipmap: Boolean = true,
): Texture? = try {
    val bmp = ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
        ?: return null

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
    } catch (_: Exception) {
        Log.d(TAG, "Texture not found: $texPath — base skin color used")
    }

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
    Log.d(TAG, "Head composite texture built: $texPath")
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

// ── Material parameter setters ────────────────────────────────────────────

private fun setParam(mat: MaterialInstance, name: String, tex: Texture, sampler: TextureSampler) {
    try { mat.setParameter(name, tex, sampler) }
    catch (e: Exception) { Log.v(TAG, "setParameter($name) skipped: ${e.message}") }
}

private fun setParam(mat: MaterialInstance, name: String, vararg floats: Float) {
    try {
        when (floats.size) {
            1 -> mat.setParameter(name, floats[0])
            4 -> mat.setParameter(name, floats[0], floats[1], floats[2], floats[3])
        }
    } catch (e: Exception) { Log.v(TAG, "setParameter($name) skipped: ${e.message}") }
}

// ── Key packing ───────────────────────────────────────────────────────────

private fun packKey(entity: Int, prim: Int): Long =
    entity.toLong().shl(32) or prim.toLong()
