package com.codeextractor.app.presentation.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

private const val MODEL_PATH = "models/source_named.glb"

// Camera defaults
private val CAM_POS = Float3(0f, 1.35f, 0.7f)
private val CAM_TGT = Float3(0f, 1.35f, 0f)
private const val SCALE = 0.35f

/**
 * Reusable 3D avatar scene.
 *
 * @param modifier       size & layout
 * @param morphWeights   FloatArray(51) morph targets
 * @param headPitch      head pitch in degrees (down = negative)
 * @param headYaw        head yaw in degrees (left = negative)
 * @param headRoll       head roll in degrees
 */
@Composable
fun AvatarScene(
    modifier: Modifier = Modifier,
    morphWeights: FloatArray? = null,
    headPitch: Float = 0f,
    headYaw: Float = 0f,
    headRoll: Float = 0f,
) {
    val engine            = rememberEngine()
    val modelLoader       = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment       = rememberEnvironment(environmentLoader)
    val modelInstance     = rememberModelInstance(modelLoader, MODEL_PATH)

    val cameraNode = rememberCameraNode(engine) {
        position = CAM_POS
    }

    Box(
        modifier = modifier.background(Color.Black)
    ) {
        Scene(
            modifier          = Modifier.fillMaxSize(),
            engine            = engine,
            modelLoader       = modelLoader,
            cameraNode        = cameraNode,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = CAM_POS,
                targetPosition    = CAM_TGT,
            ),
            environment = environment,
            onFrame     = {
                val mi = modelInstance
                if (mi != null) {
                    // Apply morph weights
                    if (morphWeights != null) {
                        applyMorphsInternal(engine, mi, morphWeights)
                    }
                    // Apply head rotation via Filament transform
                    applyHeadRotation(engine, mi, headPitch, headYaw, headRoll)
                }
            },
        ) {
            modelInstance?.let {
                ModelNode(
                    modelInstance = it,
                    scaleToUnits  = SCALE,
                    centerOrigin  = Position(0f, 0f, 0f),
                    autoAnimate   = false,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Morph application
// ─────────────────────────────────────────────────────────────────
private fun applyMorphsInternal(
    engine: com.google.android.filament.Engine,
    instance: io.github.sceneview.model.ModelInstance,
    headW: FloatArray,
) {
    val rm = engine.renderableManager
    val teethW = floatArrayOf(
        headW[14], headW[15], headW[16],
        headW[17], headW[18]
    )
    val eyeLW = floatArrayOf(
        headW[1], headW[2], headW[3], headW[4]
    )
    val eyeRW = floatArrayOf(
        headW[8], headW[9], headW[10], headW[11]
    )
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
            try { rm.setMorphWeights(ri, w, 0) }
            catch (_: Exception) { /* ignore */ }
        }
}

// ─────────────────────────────────────────────────────────────────
//  Head rotation via Filament TransformManager
// ─────────────────────────────────────────────────────────────────
private fun applyHeadRotation(
    engine: com.google.android.filament.Engine,
    instance: io.github.sceneview.model.ModelInstance,
    pitchDeg: Float,
    yawDeg: Float,
    rollDeg: Float,
) {
    // Мелкие движения — skip чтобы не тратить CPU
    if (kotlin.math.abs(pitchDeg) < 0.05f &&
        kotlin.math.abs(yawDeg) < 0.05f &&
        kotlin.math.abs(rollDeg) < 0.05f
    ) return

    val tm = engine.transformManager
    val rootEntity = instance.root

    if (!tm.hasComponent(rootEntity)) return
    val ti = tm.getInstance(rootEntity)

    // Получить текущую матрицу (содержит позицию и scale от ModelNode)
    val mat = FloatArray(16)
    tm.getTransform(ti, mat)

    // Извлечь translation (mat[12], mat[13], mat[14]) и scale
    val tx = mat[12]; val ty = mat[13]; val tz = mat[14]
    val sx = kotlin.math.sqrt(mat[0]*mat[0] + mat[1]*mat[1] + mat[2]*mat[2])
    val sy = kotlin.math.sqrt(mat[4]*mat[4] + mat[5]*mat[5] + mat[6]*mat[6])
    val sz = kotlin.math.sqrt(mat[8]*mat[8] + mat[9]*mat[9] + mat[10]*mat[10])

    // Euler → rotation matrix (YXZ order: yaw → pitch → roll)
    val p = Math.toRadians(pitchDeg.toDouble()).toFloat()
    val y = Math.toRadians(yawDeg.toDouble()).toFloat()
    val r = Math.toRadians(rollDeg.toDouble()).toFloat()

    val cp = kotlin.math.cos(p); val sp = kotlin.math.sin(p)
    val cy = kotlin.math.cos(y); val sy2 = kotlin.math.sin(y)
    val cr = kotlin.math.cos(r); val sr = kotlin.math.sin(r)

    // Rotation matrix (column-major for Filament)
    // R = Ry * Rx * Rz
    val r00 = cy * cr + sy2 * sp * sr
    val r01 = cp * sr
    val r02 = -sy2 * cr + cy * sp * sr

    val r10 = -cy * sr + sy2 * sp * cr
    val r11 = cp * cr
    val r12 = sy2 * sr + cy * sp * cr

    val r20 = sy2 * cp
    val r21 = -sp
    val r22 = cy * cp

    // Rebuild transform: Scale * Rotation * Translation
    mat[0]  = r00 * sx;  mat[1]  = r01 * sx;  mat[2]  = r02 * sx;  mat[3]  = 0f
    mat[4]  = r10 * sy;  mat[5]  = r11 * sy;  mat[6]  = r12 * sy;  mat[7]  = 0f
    mat[8]  = r20 * sz;  mat[9]  = r21 * sz;  mat[10] = r22 * sz;  mat[11] = 0f
    mat[12] = tx;        mat[13] = ty;         mat[14] = tz;        mat[15] = 1f

    tm.setTransform(ti, mat)
}


// ═══════════════════════════════════════════════════════════════════════════
// PATCH 6: VoiceScreen.kt — передать headPitch/Yaw/Roll в AvatarScene
// ═══════════════════════════════════════════════════════════════════════════
//
// В VoiceScreen, внутри Scaffold, блок AvatarScene:
//
// БЫЛО:
//
//     AvatarScene(
//         modifier     = Modifier.fillMaxSize(),
//         morphWeights = renderState.morphWeights,
//     )
//
// СТАЛО:
//
//     AvatarScene(
//         modifier     = Modifier.fillMaxSize(),
//         morphWeights = renderState.morphWeights,
//         headPitch    = renderState.headPitch,
//         headYaw      = renderState.headYaw,
//         headRoll     = renderState.headRoll,
//     )

// ═══════════════════════════════════════════════════════════════════════════
// КОНЕЦ ПАТЧЕЙ
// ═══════════════════════════════════════════════════════════════════════════
//
// ИТОГО ИЗМЕНЕНИЙ:
//
// НОВЫЕ ФАЙЛЫ (2):
//   domain/avatar/physics/HeadMotionEngine.kt
//   domain/avatar/CoArticulator.kt
//
// ПОЛНАЯ ЗАМЕНА (3):
//   domain/avatar/VisemeMapper.kt
//   data/avatar/AvatarAnimatorImpl.kt
//   presentation/avatar/AvatarScene.kt
//
// ТОЧЕЧНОЕ ИЗМЕНЕНИЕ (1):
//   presentation/voice/VoiceScreen.kt — AvatarScene вызов
//
// Hilt-модуль, ARKit.kt, AvatarModels.kt, AudioDSPAnalyzer.kt,
// ProsodyTracker.kt, FacePhysicsEngine.kt, IdleAnimator.kt —
// БЕЗ ИЗМЕНЕНИЙ (полностью совместимы)
// ═══════════════════════════════════════════════════════════════════════════
