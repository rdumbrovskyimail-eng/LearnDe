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

// Camera defaults (found via AvatarTestScreen debug panel)
private val CAM_POS = Float3(0f, 1.35f, 0.7f)
private val CAM_TGT = Float3(0f, 1.35f, 0f)
private const val SCALE = 0.35f

/**
 * Reusable 3D avatar scene — just the head, no test UI.
 * Drop into any screen with a Modifier that defines the size.
 *
 * @param modifier       size & layout — caller decides (e.g. weight(0.5f))
 * @param morphWeights   FloatArray(51) to apply each frame (from AvatarAnimator)
 */
@Composable
fun AvatarScene(
    modifier: Modifier = Modifier,
    morphWeights: FloatArray? = null,
) {
    val engine            = rememberEngine()
    val modelLoader       = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment       = rememberEnvironment(environmentLoader)
    val modelInstance      = rememberModelInstance(modelLoader, MODEL_PATH)

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
                // Apply morph weights every frame if provided
                val mi = modelInstance
                if (morphWeights != null && mi != null) {
                    applyMorphsInternal(engine, mi, morphWeights)
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
//  Internal morph application (same logic as AvatarTestScreen)
// ─────────────────────────────────────────────────────────────────
private fun applyMorphsInternal(
    engine: com.google.android.filament.Engine,
    instance: io.github.sceneview.model.ModelInstance,
    headW: FloatArray,
) {
    val rm = engine.renderableManager
    val teethW = floatArrayOf(
        headW[14], headW[15], headW[16],  // jawForward, jawLeft, jawRight
        headW[17], headW[18]              // jawOpen, mouthClose
    )
    val eyeLW = floatArrayOf(
        headW[1], headW[2], headW[3], headW[4]   // lookDown/In/Out/Up Left
    )
    val eyeRW = floatArrayOf(
        headW[8], headW[9], headW[10], headW[11]  // lookDown/In/Out/Up Right
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
