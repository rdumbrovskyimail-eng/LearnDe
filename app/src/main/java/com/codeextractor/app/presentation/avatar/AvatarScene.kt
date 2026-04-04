package com.codeextractor.app.presentation.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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

    // ── FIX #1: rememberUpdatedState для ВСЕХ мутабельных значений ──
    val currentModelInstance by rememberUpdatedState(modelInstance)
    val currentMorphWeights  by rememberUpdatedState(morphWeights)
    val currentPitch         by rememberUpdatedState(headPitch)
    val currentYaw           by rememberUpdatedState(headYaw)
    val currentRoll          by rememberUpdatedState(headRoll)

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
                // ── FIX: используем currentModelInstance (всегда актуальный) ──
                val mi = currentModelInstance
                if (mi != null) {
                    val w = currentMorphWeights
                    if (w != null) {
                        applyMorphsInternal(engine, mi, w)
                    }
                    applyHeadRotation(engine, mi, currentPitch, currentYaw, currentRoll)
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

    // Teeth: jawForward(14), jawLeft(15), jawRight(16), jawOpen(17), mouthClose(18)
    val teethW = floatArrayOf(
        headW[14], headW[15], headW[16],
        headW[17], headW[18]
    )

    // EyeLeft: eyeLookDownLeft(1), eyeLookInLeft(2), eyeLookOutLeft(3), eyeLookUpLeft(4)
    val eyeLW = floatArrayOf(
        headW[1], headW[2], headW[3], headW[4]
    )

    // EyeRight: eyeLookDownRight(8), eyeLookInRight(9), eyeLookOutRight(10), eyeLookUpRight(11)
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
//  FIX #3: Правильный column-major порядок матрицы
// ─────────────────────────────────────────────────────────────────
private fun applyHeadRotation(
    engine: com.google.android.filament.Engine,
    instance: io.github.sceneview.model.ModelInstance,
    pitchDeg: Float,
    yawDeg: Float,
    rollDeg: Float,
) {
    if (kotlin.math.abs(pitchDeg) < 0.05f &&
        kotlin.math.abs(yawDeg) < 0.05f &&
        kotlin.math.abs(rollDeg) < 0.05f
    ) return

    val tm = engine.transformManager
    val rootEntity = instance.root

    if (!tm.hasComponent(rootEntity)) return
    val ti = tm.getInstance(rootEntity)

    val mat = FloatArray(16)
    tm.getTransform(ti, mat)

    // Извлечь translation и scale из текущей матрицы
    val tx = mat[12]; val ty = mat[13]; val tz = mat[14]
    val sx = kotlin.math.sqrt(mat[0]*mat[0] + mat[1]*mat[1] + mat[2]*mat[2])
    val sy = kotlin.math.sqrt(mat[4]*mat[4] + mat[5]*mat[5] + mat[6]*mat[6])
    val sz = kotlin.math.sqrt(mat[8]*mat[8] + mat[9]*mat[9] + mat[10]*mat[10])

    // Euler → rotation matrix (YXZ: yaw → pitch → roll)
    val p = Math.toRadians(pitchDeg.toDouble()).toFloat()
    val y = Math.toRadians(yawDeg.toDouble()).toFloat()
    val r = Math.toRadians(rollDeg.toDouble()).toFloat()

    val cp = kotlin.math.cos(p); val sp = kotlin.math.sin(p)
    val cy = kotlin.math.cos(y); val sy2 = kotlin.math.sin(y)
    val cr = kotlin.math.cos(r); val sr = kotlin.math.sin(r)

    // R = Ry * Rx * Rz  (row-major notation)
    val r00 = cy * cr + sy2 * sp * sr
    val r01 = cp * sr
    val r02 = -sy2 * cr + cy * sp * sr

    val r10 = -cy * sr + sy2 * sp * cr
    val r11 = cp * cr
    val r12 = sy2 * sr + cy * sp * cr

    val r20 = sy2 * cp
    val r21 = -sp
    val r22 = cy * cp

    // ── FIX: Column-major для Filament ──
    // Столбец 0 (X-axis)
    mat[0]  = r00 * sx;  mat[1]  = r10 * sx;  mat[2]  = r20 * sx;  mat[3]  = 0f
    // Столбец 1 (Y-axis)
    mat[4]  = r01 * sy;  mat[5]  = r11 * sy;  mat[6]  = r21 * sy;  mat[7]  = 0f
    // Столбец 2 (Z-axis)
    mat[8]  = r02 * sz;  mat[9]  = r12 * sz;  mat[10] = r22 * sz;  mat[11] = 0f
    // Столбец 3 (Translation)
    mat[12] = tx;        mat[13] = ty;         mat[14] = tz;        mat[15] = 1f

    tm.setTransform(ti, mat)
}