package com.codeextractor.app.presentation.avatar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import dev.romainguy.kotlin.math.Float3
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
private const val MODEL_PATH = "models/source_named.glb"

// Текстуры из assets/models/ — кладутся туда вручную после редактора
private const val HEAD_TEXTURE = "models/head_texture.png"
private const val EYES_TEXTURE = "models/eyes_texture.png"
private const val TEETH_TEXTURE = "models/teeth_texture.png"

private val CAM_POS = Float3(0f, 1.35f, 0.7f)
private val CAM_TGT = Float3(0f, 1.35f, 0f)
private const val SCALE = 0.35f

@Composable
fun AvatarScene(
    modifier: Modifier = Modifier,
    morphWeights: FloatArray? = null,
    headPitch: Float = 0f,
    headYaw: Float = 0f,
    headRoll: Float = 0f,
) {
    val ctx = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment = rememberEnvironment(environmentLoader)

    var modelInstance by remember { mutableStateOf<ModelInstance?>(null) }

    // ── 1. Загрузка патченной модели ──
    LaunchedEffect(modelLoader) {
        val buffer = withContext(Dispatchers.IO) {
            val patchedFile = File(ctx.cacheDir, "patched_model.glb")
            if (patchedFile.exists()) patchedFile.delete()
            com.codeextractor.app.editor.GlbTextureEditor(ctx)
                .preparePatchedModel(MODEL_PATH)
            val bytes = patchedFile.readBytes()
            ByteBuffer.allocateDirect(bytes.size).also { it.put(bytes); it.rewind() }
        }
        modelInstance = modelLoader.createModelInstance(buffer)
    }

    // ── 2. Применение текстур и PBR ──
    LaunchedEffect(modelInstance) {
        val mi = modelInstance ?: return@LaunchedEffect
        val rm = engine.renderableManager

        // Собираем MaterialInstance по morph count
        var headMat: MaterialInstance? = null
        var teethMat: MaterialInstance? = null
        var eyeLMat: MaterialInstance? = null
        var eyeRMat: MaterialInstance? = null
        var eyeCount = 0

        for (entity in mi.entities) {
            if (!rm.hasComponent(entity)) continue
            val ri = rm.getInstance(entity)
            val morphCount = try { rm.getMorphTargetCount(ri) } catch (_: Exception) { 0 }
            for (prim in 0 until rm.getPrimitiveCount(ri)) {
                val mat = try { rm.getMaterialInstanceAt(ri, prim) } catch (_: Exception) { continue }
                val matName = mat.name?.lowercase() ?: ""

                // 1. Изолируем полость рта и красим в розово-оранжевый (плоть)
                if (matName.contains("mouth") || matName.contains("cavity") || matName.contains("tongue")) {
                    try {
                        mat.setParameter("baseColorMap", whiteTex, defaultSampler)
                        mat.setParameter("baseColorFactor", 0.85f, 0.45f, 0.38f, 1f) // Цвет рта
                        mat.setParameter("roughnessFactor", 0.8f) // Внутри рта меньше бликов
                        mat.setParameter("metallicFactor", 0f)
                    } catch (_: Exception) {}
                    continue // Пропускаем, чтобы он не перезаписал кожу лица!
                }

                // 2. Безопасное присвоение (берем только первый подходящий материал)
                when (morphCount) {
                    51 -> if (headMat == null) headMat = mat
                    5 -> if (teethMat == null) teethMat = mat
                    4 -> { 
                        if (eyeCount == 0) eyeLMat = mat 
                        else if (eyeCount == 1) eyeRMat = mat
                        eyeCount++
                    }
                }
            }
        }

        // ════════════════════════════════════════════════════════
        // Белая текстура-заглушка.
        // SceneView НЕ загружает dummy текстуру из GLB binary chunk.
        // Без реальной текстуры baseColorFactor ИГНОРИРУЕТСЯ.
        // Поэтому если PNG файла нет — привязываем белую текстуру,
        // и тогда baseColorFactor работает как цвет.
        // ════════════════════════════════════════════════════════
        val whiteBmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        Canvas(whiteBmp).drawColor(android.graphics.Color.WHITE)
        val whiteTex = Texture.Builder()
            .width(4).height(4).levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .usage(Texture.Usage.SAMPLEABLE or Texture.Usage.UPLOADABLE)
            .build(engine)
        TextureHelper.setBitmap(engine, whiteTex, 0, whiteBmp)
        whiteBmp.recycle()

        val defaultSampler = TextureSampler().apply {
            setMinFilter(TextureSampler.MinFilter.LINEAR)
            setMagFilter(TextureSampler.MagFilter.LINEAR)
            setWrapModeS(TextureSampler.WrapMode.CLAMP_TO_EDGE)
            setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE)
        }

        // ════════════════════════════════════════════════════════
        // ГОЛОВА (51 morph) — head_texture.png
        // Включает лицо, шею, волосы, РОТ (MOUTH_INNER).
        // Рот розовый потому что composite bitmap заливает
        // bgColor = SKIN_COLOR для незакрытых зон.
        // ════════════════════════════════════════════════════════
        headMat?.let { mat ->
            val loaded = loadTextureFromAssets(ctx, engine, HEAD_TEXTURE)
            if (loaded != null) {
                val sampler = TextureSampler().apply {
                    setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                    setMagFilter(TextureSampler.MagFilter.LINEAR)
                    setWrapModeS(TextureSampler.WrapMode.CLAMP_TO_EDGE)
                    setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE)
                }
                try {
                    mat.setParameter("baseColorMap", loaded, sampler)
                    mat.setParameter("baseColorFactor", 1f, 1f, 1f, 1f)
                } catch (e: Exception) { Log.e(TAG, "Head texture bind failed", e) }
            } else {
                // Нет head_texture.png — белая заглушка + цвет кожи
                try {
                    mat.setParameter("baseColorMap", whiteTex, defaultSampler)
                    mat.setParameter("baseColorFactor", 0.73f, 0.56f, 0.38f, 1f)
                } catch (_: Exception) {}
            }
            try { mat.setParameter("roughnessFactor", 0.6f) } catch (_: Exception) {}
            try { mat.setParameter("metallicFactor", 0f) } catch (_: Exception) {}
        }

        // ════════════════════════════════════════════════════════
        // ЗУБЫ (5 morph) — teeth_texture.png
        // Аналогично лицу. Если файла нет — белая текстура + белый цвет.
        // ════════════════════════════════════════════════════════
        teethMat?.let { mat ->
            val loaded = loadTextureFromAssets(ctx, engine, TEETH_TEXTURE)
            if (loaded != null) {
                val sampler = TextureSampler().apply {
                    setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                    setMagFilter(TextureSampler.MagFilter.LINEAR)
                    setWrapModeS(TextureSampler.WrapMode.CLAMP_TO_EDGE)
                    setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE)
                }
                try {
                    mat.setParameter("baseColorMap", loaded, sampler)
                    mat.setParameter("baseColorFactor", 1f, 1f, 1f, 1f)
                } catch (e: Exception) { Log.e(TAG, "Teeth texture bind failed", e) }
            } else {
                // Нет teeth_texture.png — белая заглушка + белый/кремовый цвет
                try {
                    mat.setParameter("baseColorMap", whiteTex, defaultSampler)
                    mat.setParameter("baseColorFactor", 0.92f, 0.90f, 0.85f, 1f)
                } catch (_: Exception) {}
            }
            try { mat.setParameter("roughnessFactor", 0.3f) } catch (_: Exception) {}
            try { mat.setParameter("metallicFactor", 0f) } catch (_: Exception) {}
        }

        // ════════════════════════════════════════════════════════
        // ГЛАЗА (4 morph) — eyes_texture.png
        // Аналогично лицу. Если файла нет — белая текстура + белый цвет.
        // ════════════════════════════════════════════════════════
        listOf(eyeLMat, eyeRMat).filterNotNull().forEach { mat ->
            val loaded = loadTextureFromAssets(ctx, engine, EYES_TEXTURE)
            if (loaded != null) {
                val sampler = TextureSampler().apply {
                    setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                    setMagFilter(TextureSampler.MagFilter.LINEAR)
                    setWrapModeS(TextureSampler.WrapMode.REPEAT)
                    setWrapModeT(TextureSampler.WrapMode.REPEAT)
                }
                try {
                    mat.setParameter("baseColorMap", loaded, sampler)
                    mat.setParameter("baseColorFactor", 1f, 1f, 1f, 1f)
                } catch (e: Exception) { Log.e(TAG, "Eye texture bind failed", e) }
            } else {
                // Нет eyes_texture.png — белая заглушка + белый цвет
                try {
                    mat.setParameter("baseColorMap", whiteTex, defaultSampler)
                    mat.setParameter("baseColorFactor", 0.95f, 0.95f, 0.95f, 1f)
                } catch (_: Exception) {}
            }
            try { mat.setParameter("roughnessFactor", 0.05f) } catch (_: Exception) {}
            try { mat.setParameter("metallicFactor", 0f) } catch (_: Exception) {}
        }
    }

    val currentMorphWeights by rememberUpdatedState(morphWeights)
    val currentPitch by rememberUpdatedState(headPitch)
    val currentYaw by rememberUpdatedState(headYaw)
    val currentRoll by rememberUpdatedState(headRoll)

    val cameraNode = rememberCameraNode(engine) { position = CAM_POS }

    Box(modifier = modifier.background(Color.Black)) {
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = CAM_POS,
                targetPosition = CAM_TGT,
            ),
            environment = environment,
            onFrame = {
                val mi = modelInstance
                if (mi != null) {
                    val w = currentMorphWeights
                    if (w != null) applyMorphsInternal(engine, mi, w)
                    applyHeadRotation(engine, mi, currentPitch, currentYaw, currentRoll)
                }
            },
        ) {
            modelInstance?.let {
                ModelNode(
                    modelInstance = it,
                    scaleToUnits = SCALE,
                    centerOrigin = Position(0f, 0f, 0f),
                    autoAnimate = false,
                )
            }
        }

        if (modelInstance == null) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * Загружает PNG из assets, создаёт Filament Texture с mipmaps.
 * Возвращает null если файла нет.
 */
private fun loadTextureFromAssets(
    ctx: android.content.Context,
    engine: com.google.android.filament.Engine,
    assetPath: String,
): Texture? {
    return try {
        val bmp = ctx.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            ?: return null
        val mipLevels = (kotlin.math.log2(bmp.width.toFloat())).toInt() + 1
        val tex = Texture.Builder()
            .width(bmp.width).height(bmp.height).levels(mipLevels)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .usage(
                Texture.Usage.SAMPLEABLE or Texture.Usage.COLOR_ATTACHMENT or
                        Texture.Usage.UPLOADABLE or Texture.Usage.GEN_MIPMAPPABLE
            )
            .build(engine)
        TextureHelper.setBitmap(engine, tex, 0, bmp)
        tex.generateMipmaps(engine)
        bmp.recycle()
        Log.d(TAG, "Loaded texture: $assetPath")
        tex
    } catch (_: java.io.FileNotFoundException) {
        Log.d(TAG, "No texture in assets: $assetPath")
        null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load texture: $assetPath", e)
        null
    }
}

private fun applyMorphsInternal(
    engine: com.google.android.filament.Engine,
    instance: io.github.sceneview.model.ModelInstance,
    headW: FloatArray,
) {
    val rm = engine.renderableManager
    val teethW = floatArrayOf(headW[14], headW[15], headW[16], headW[17], headW[18])
    val eyeLW  = floatArrayOf(headW[1], headW[2], headW[3], headW[4])
    val eyeRW  = floatArrayOf(headW[8], headW[9], headW[10], headW[11])
    var eye4 = 0
    instance.entities
        .filter { rm.hasComponent(it) }
        .map    { rm.getInstance(it) }
        .forEach { ri ->
            val count = rm.getMorphTargetCount(ri)
            if (count <= 0) return@forEach
            val w = when (count) {
                51   -> headW
                5    -> teethW
                4    -> if (eye4++ == 0) eyeLW else eyeRW
                else -> return@forEach
            }
            try { rm.setMorphWeights(ri, w, 0) } catch (_: Exception) {}
        }
}

private fun applyHeadRotation(
    engine: com.google.android.filament.Engine,
    instance: io.github.sceneview.model.ModelInstance,
    pitchDeg: Float,
    yawDeg: Float,
    rollDeg: Float,
) {
    if (kotlin.math.abs(pitchDeg) < 0.05f &&
        kotlin.math.abs(yawDeg) < 0.05f &&
        kotlin.math.abs(rollDeg) < 0.05f) return

    val tm = engine.transformManager
    val rootEntity = instance.root
    if (!tm.hasComponent(rootEntity)) return
    val ti = tm.getInstance(rootEntity)

    val mat = FloatArray(16)
    tm.getTransform(ti, mat)

    val tx = mat[12]; val ty = mat[13]; val tz = mat[14]
    val sx = kotlin.math.sqrt(mat[0]*mat[0] + mat[1]*mat[1] + mat[2]*mat[2])
    val sy = kotlin.math.sqrt(mat[4]*mat[4] + mat[5]*mat[5] + mat[6]*mat[6])
    val sz = kotlin.math.sqrt(mat[8]*mat[8] + mat[9]*mat[9] + mat[10]*mat[10])

    val p = Math.toRadians(pitchDeg.toDouble()).toFloat()
    val y = Math.toRadians(yawDeg.toDouble()).toFloat()
    val r = Math.toRadians(rollDeg.toDouble()).toFloat()

    val cp = kotlin.math.cos(p); val sp = kotlin.math.sin(p)
    val cy = kotlin.math.cos(y); val sy2 = kotlin.math.sin(y)
    val cr = kotlin.math.cos(r); val sr = kotlin.math.sin(r)

    val r00 = cy * cr + sy2 * sp * sr; val r01 = cp * sr;  val r02 = -sy2 * cr + cy * sp * sr
    val r10 = -cy * sr + sy2 * sp * cr; val r11 = cp * cr; val r12 = sy2 * sr + cy * sp * cr
    val r20 = sy2 * cp; val r21 = -sp; val r22 = cy * cp

    mat[0] = r00*sx; mat[1] = r10*sx; mat[2] = r20*sx;  mat[3] = 0f
    mat[4] = r01*sy; mat[5] = r11*sy; mat[6] = r21*sy;  mat[7] = 0f
    mat[8] = r02*sz; mat[9] = r12*sz; mat[10] = r22*sz; mat[11] = 0f
    mat[12] = tx;    mat[13] = ty;    mat[14] = tz;      mat[15] = 1f

    tm.setTransform(ti, mat)
}
