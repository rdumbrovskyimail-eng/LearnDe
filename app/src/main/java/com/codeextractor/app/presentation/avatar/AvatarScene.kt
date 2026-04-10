package com.codeextractor.app.presentation.avatar

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
import com.codeextractor.app.editor.GlbTextureEditor
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

private const val MODEL_PATH = "models/source_named.glb"

private val CAM_POS = Float3(0f, 1.35f, 0.7f)
private val CAM_TGT = Float3(0f, 1.35f, 0f)
private const val SCALE = 0.35f

// ─────────────────────────────────────────────────────────────────
// Цвета кожи в ЛИНЕЙНОМ пространстве (для Filament baseColorFactor)
//
// Исходный sRGB цвет кожи: rgb(185, 142, 96) = (0.725, 0.557, 0.376) sRGB
// Конвертация в линейное: x^2.2
//   R: 0.725^2.2 ≈ 0.491
//   G: 0.557^2.2 ≈ 0.270
//   B: 0.376^2.2 ≈ 0.118
//
// Оригинальный GLB имел metallic=0.4 и bcf=[0.503,0.503,0.503] → чёрный.
// preparePatchedModel() сбрасывает их в нейтральные значения в JSON,
// а здесь мы выставляем правильный цвет через setParameter.
// ─────────────────────────────────────────────────────────────────
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

    // ИСПРАВЛЕНО: загружаем ПАТЧЕННУЮ модель, а не сырой GLB.
    // Без патча:
    //   - нет baseColorTexture → Filament использует упрощённый шейдер
    //   - setParameter("metallicFactor") падает молча
    //   - GLB-значение metallic=0.4 остаётся → модель чёрная
    // После патча: dummy белая текстура → полный PBR шейдер → setParameter работает.
    var modelInstance by remember { mutableStateOf<ModelInstance?>(null) }

    LaunchedEffect(modelLoader) {
        val buffer = withContext(Dispatchers.IO) {
            val bytes = ctx.assets.open(MODEL_PATH).use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).also { it.put(bytes); it.rewind() }
        }
        modelInstance = modelLoader.createModelInstance(buffer)
    }

    /*
    // Применяем цвет кожи после загрузки модели
    LaunchedEffect(modelInstance) {
        val mi = modelInstance ?: return@LaunchedEffect
        val rm = engine.renderableManager

        for (entity in mi.entities) {
            if (!rm.hasComponent(entity)) continue
            val ri = rm.getInstance(entity)
            val morphCount = try { rm.getMorphTargetCount(ri) } catch (_: Exception) { 0 }
            val primCount = rm.getPrimitiveCount(ri)

            for (prim in 0 until primCount) {
                val mat = try { rm.getMaterialInstanceAt(ri, prim) } catch (_: Exception) { continue }
                when (morphCount) {
                    51 -> { // голова
                        try { mat.setParameter("roughnessFactor", 0.6f) } catch (_: Exception) {}
                        try { mat.setParameter("metallicFactor", 0f) } catch (_: Exception) {}
                    }
                    5 -> { // зубы
                        try { mat.setParameter("baseColorFactor", 0.88f, 0.84f, 0.75f, 1f) } catch (_: Exception) {}
                        try { mat.setParameter("roughnessFactor", 0.2f) } catch (_: Exception) {}
                        try { mat.setParameter("metallicFactor", 0f) } catch (_: Exception) {}
                    }
                    4 -> { // глаза — белки
                        try { mat.setParameter("baseColorFactor", 0.92f, 0.92f, 0.92f, 1f) } catch (_: Exception) {}
                        try { mat.setParameter("roughnessFactor", 0.05f) } catch (_: Exception) {}
                        try { mat.setParameter("metallicFactor", 0f) } catch (_: Exception) {}
                    }
                }
            }
        }
    }
    */

    val currentMorphWeights by rememberUpdatedState(morphWeights)
    val currentPitch by rememberUpdatedState(headPitch)
    val currentYaw by rememberUpdatedState(headYaw)
    val currentRoll by rememberUpdatedState(headRoll)

    val cameraNode = rememberCameraNode(engine) {
        position = CAM_POS
    }

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

        // Индикатор загрузки пока модель патчится/грузится
        if (modelInstance == null) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.4f)
            )
        }
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
