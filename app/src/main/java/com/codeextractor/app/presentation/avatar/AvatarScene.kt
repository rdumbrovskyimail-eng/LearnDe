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

// \u2500\u2500 \u0420\u0435\u0441\u0443\u0440\u0441\u044b \u0430\u0432\u0430\u0442\u0430\u0440\u0430 1 (\u043c\u0443\u0436\u0441\u043a\u043e\u0439) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
private const val MODEL_PATH_1    = "models/test.glb"
private const val HEAD_TEXTURE_1  = "models/head_texture.png"
private const val EYES_TEXTURE_1  = "models/eyes_texture.png"
private const val TEETH_TEXTURE_1 = "models/teeth_texture.png"

// \u2500\u2500 \u0420\u0435\u0441\u0443\u0440\u0441\u044b \u0430\u0432\u0430\u0442\u0430\u0440\u0430 2 (\u0436\u0435\u043d\u0441\u043a\u0438\u0439) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
private const val MODEL_PATH_2    = "models/test2.glb"
private const val HEAD_TEXTURE_2  = "models/head_texture2.png"
private const val EYES_TEXTURE_2  = "models/eyes_texture2.png"
private const val TEETH_TEXTURE_2 = "models/teeth_texture2.png"

private const val COMPOSITE_SIZE = 1024

// \u2500\u2500 \u041a\u0430\u043c\u0435\u0440\u0430 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
private val CAM_POS = dev.romainguy.kotlin.math.Float3(0f, 1.35f, 0.70f)
private val CAM_TGT = dev.romainguy.kotlin.math.Float3(0f, 1.35f, 0.00f)
private const val MODEL_SCALE = 0.35f

// \u2500\u2500 Pre-allocated array \u0434\u043b\u044f \u043f\u043e\u0432\u043e\u0440\u043e\u0442\u0430 \u0433\u043e\u043b\u043e\u0432\u044b (zero-alloc \u0432 onFrame) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
private val reusableTransformMatrix = FloatArray(16)

/**
 * AvatarScene \u2014 3D Avatar Renderer
 *
 * \u0410\u0420\u0425\u0418\u0422\u0415\u041a\u0422\u0423\u0420\u0410:
 *   1. \u0417\u0430\u0433\u0440\u0443\u0437\u043a\u0430 \u043c\u043e\u0434\u0435\u043b\u0438 \u2014 LaunchedEffect(modelLoader, avatarIndex)
 *   2. \u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0430 \u043c\u0430\u0442\u0435\u0440\u0438\u0430\u043b\u043e\u0432 \u2014 LaunchedEffect(modelInstance, avatarIndex)
 *   3. \u0420\u0435\u043d\u0434\u0435\u0440-\u0446\u0438\u043a\u043b \u2014 Scene.onFrame (60 fps):
 *      \u2022 applyMorphWeights \u2014 ARKit blend-shapes (\u0433\u0443\u0431\u044b, \u043c\u043e\u0440\u0433\u0430\u043d\u0438\u0435, \u043c\u0438\u043c\u0438\u043a\u0430)
 *      \u2022 applyHeadRotation \u2014 \u043f\u043e\u0432\u043e\u0440\u043e\u0442 root entity
 *
 * \u0413\u041b\u0410\u0417\u0410: glb-\u043c\u0435\u0448\u0438 \u0433\u043b\u0430\u0437 \u044f\u0432\u043b\u044f\u044e\u0442\u0441\u044f \u0434\u043e\u0447\u0435\u0440\u043d\u0438\u043c\u0438 \u043e\u0431\u044a\u0435\u043a\u0442\u0430\u043c\u0438 \u0447\u0435\u0440\u0435\u043f\u0430 \u0438 \u0432\u0440\u0430\u0449\u0430\u044e\u0442\u0441\u044f
 * \u0432\u043c\u0435\u0441\u0442\u0435 \u0441 \u043d\u0438\u043c \u2014 \u044d\u0442\u043e \u043f\u0440\u0430\u0432\u0438\u043b\u044c\u043d\u043e\u0435 \u043f\u043e\u0432\u0435\u0434\u0435\u043d\u0438\u0435. \u041d\u0430\u043f\u0440\u0430\u0432\u043b\u0435\u043d\u0438\u0435 \u0432\u0437\u0433\u043b\u044f\u0434\u0430 \u0443\u043f\u0440\u0430\u0432\u043b\u044f\u0435\u0442\u0441\u044f
 * \u0447\u0435\u0440\u0435\u0437 ARKit blend-shapes (eyeLookUp/Down/In/Out), \u0430 \u043d\u0435 \u0447\u0435\u0440\u0435\u0437 transform.
 *
 * PBR \u041c\u0410\u0422\u0415\u0420\u0418\u0410\u041b\u042b:
 *   \u0413\u043e\u043b\u043e\u0432\u0430  \u2014 roughness 0.48 (\u043c\u0430\u0442\u043e\u0432\u0430\u044f \u043a\u043e\u0436\u0430)
 *   \u0417\u0443\u0431\u044b    \u2014 roughness 0.35 \u0441 \u0442\u0435\u043a\u0441\u0442\u0443\u0440\u043e\u0439 / 0.85 (\u0441\u043b\u0438\u0437\u0438\u0441\u0442\u0430\u044f) \u0431\u0435\u0437 \u0442\u0435\u043a\u0441\u0442\u0443\u0440\u044b
 *   \u0413\u043b\u0430\u0437\u0430   \u2014 roughness 0.02 (\u0440\u043e\u0433\u043e\u0432\u0438\u0446\u0430)
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

    // \u2500\u2500 \u0422\u0435\u043a\u0443\u0449\u0438\u0439 \u0430\u0432\u0430\u0442\u0430\u0440: 1 = \u043c\u0443\u0436\u0441\u043a\u043e\u0439, 2 = \u0436\u0435\u043d\u0441\u043a\u0438\u0439 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    var avatarIndex    by remember { mutableStateOf(1) }
    var modelInstance  by remember { mutableStateOf<ModelInstance?>(null) }
    var materialsReady by remember { mutableStateOf(false) }

    val trackedTextures = remember { mutableListOf<Texture>() }
    val frameSnapshot   = remember { ZeroAllocRenderState() }
    var whiteTex        by remember { mutableStateOf<Texture?>(null) }

    fun modelPath() = if (avatarIndex == 1) MODEL_PATH_1    else MODEL_PATH_2
    fun headTex()   = if (avatarIndex == 1) HEAD_TEXTURE_1  else HEAD_TEXTURE_2
    fun eyesTex()   = if (avatarIndex == 1) EYES_TEXTURE_1  else EYES_TEXTURE_2
    fun teethTex()  = if (avatarIndex == 1) TEETH_TEXTURE_1 else TEETH_TEXTURE_2

    // \u2500\u2500 \u041e\u0447\u0438\u0441\u0442\u043a\u0430 GPU-\u043f\u0430\u043c\u044f\u0442\u0438 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    DisposableEffect(engine) {
        onDispose {
            Log.d(TAG, "Disposing ${trackedTextures.size} textures")
            for (tex in trackedTextures) {
                try { engine.destroyTexture(tex) } catch (e: Exception) { Log.w(TAG, "destroyTexture failed", e) }
            }
            trackedTextures.clear()
            whiteTex?.let {
                try { engine.destroyTexture(it) } catch (e: Exception) { Log.w(TAG, "destroyTexture (white) failed", e) }
            }
        }
    }

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    //  \u0417\u0410\u0413\u0420\u0423\u0417\u041a\u0410 \u041c\u041e\u0414\u0415\u041b\u0418
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    LaunchedEffect(modelLoader, avatarIndex) {
        modelInstance  = null
        materialsReady = false

        val buffer = withContext(Dispatchers.IO) {
            // GlbTextureEditor \u0432\u0441\u0435\u0433\u0434\u0430 \u043f\u0438\u0448\u0435\u0442 \u0432 patched_model.glb
            val editorOutput = File(ctx.cacheDir, "patched_model.glb")
            val patchedFile  = File(ctx.cacheDir, "patched_model_$avatarIndex.glb")

            if (patchedFile.exists()) patchedFile.delete()

            com.codeextractor.app.editor.GlbTextureEditor(ctx)
                .preparePatchedModel(modelPath())

            // \u041f\u0435\u0440\u0435\u0438\u043c\u0435\u043d\u043e\u0432\u044b\u0432\u0430\u0435\u043c \u0432 \u0444\u0430\u0439\u043b \u0441 \u0438\u043d\u0434\u0435\u043a\u0441\u043e\u043c \u0430\u0432\u0430\u0442\u0430\u0440\u0430
            editorOutput.renameTo(patchedFile)

            val bytes = patchedFile.readBytes()
            ByteBuffer.allocateDirect(bytes.size).also { it.put(bytes); it.rewind() }
        }
        modelInstance = modelLoader.createModelInstance(buffer)
        Log.d(TAG, "Model loaded [avatar=$avatarIndex]: ${modelInstance?.entities?.size} entities")
    }

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    //  \u041d\u0410\u0421\u0422\u0420\u041e\u0419\u041a\u0410 \u041c\u0410\u0422\u0415\u0420\u0418\u0410\u041b\u041e\u0412
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    LaunchedEffect(modelInstance, avatarIndex) {
        val mi = modelInstance ?: return@LaunchedEffect
        val rm = engine.renderableManager

        withContext(Dispatchers.IO) {

            // \u041e\u0441\u0432\u043e\u0431\u043e\u0436\u0434\u0430\u0435\u043c \u0442\u0435\u043a\u0441\u0442\u0443\u0440\u044b \u043f\u0440\u0435\u0434\u044b\u0434\u0443\u0449\u0435\u0433\u043e \u0430\u0432\u0430\u0442\u0430\u0440\u0430
            for (tex in trackedTextures) {
                try { engine.destroyTexture(tex) } catch (e: Exception) { Log.w(TAG, "destroyTexture (swap) failed", e) }
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

            for (entity in mi.entities) {
                if (!rm.hasComponent(entity)) continue
                val ri         = rm.getInstance(entity)
                val morphCount = try { rm.getMorphTargetCount(ri) } catch (_: Exception) { 0 }
                val primCount  = rm.getPrimitiveCount(ri)
                if (primCount <= 0 || morphCount <= 0) continue

                val mat = try { rm.getMaterialInstanceAt(ri, 0) } catch (_: Exception) { null }
                    ?: continue

                when (identifyMeshType(mi, entity, morphCount, eyeCount)) {
                    ARKit.MeshType.HEAD  -> headMat  = mat
                    ARKit.MeshType.TEETH -> teethMat = mat
                    ARKit.MeshType.EYE_LEFT,
                    ARKit.MeshType.EYE_RIGHT -> {
                        if (eyeCount == 0) eyeLMat = mat else eyeRMat = mat
                        eyeCount++
                    }
                    ARKit.MeshType.OTHER -> { /* \u043d\u0435 \u0442\u0440\u043e\u0433\u0430\u0435\u043c */ }
                }
            }

            // \u2500\u2500 \u0413\u041e\u041b\u041e\u0412\u0410 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
            headMat?.let { mat ->
                val tex = buildHeadCompositeTexture(ctx, engine, headTex())
                    ?.also { trackedTextures.add(it) }
                val sampler = if (tex != null) buildMipmapSampler(anisotropy = 8f) else defaultSampler
                setParam(mat, "baseColorMap",    tex ?: wt, sampler)
                setParam(mat, "baseColorFactor", 1f, 1f, 1f, 1f)
                setParam(mat, "roughnessFactor", 0.48f)
                setParam(mat, "metallicFactor",  0.00f)
            }

            // \u2500\u2500 \u0417\u0423\u0411\u042b \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
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
                    Log.d(TAG, "Teeth: no texture \u2192 mouth interior fallback")
                }
            }

            // \u2500\u2500 \u0413\u041b\u0410\u0417\u0410 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
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

        materialsReady = true
        Log.d(TAG, "Materials ready [avatar=$avatarIndex]. Textures: ${trackedTextures.size}")
    }

    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
    //  RENDER
    // \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
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

                // 1. Blend-shapes: \u043c\u0438\u043c\u0438\u043a\u0430, \u043c\u043e\u0440\u0433\u0430\u043d\u0438\u0435, \u0440\u043e\u0442 \u0438 \u0442.\u0434.
                applyMorphWeights(engine, mi, frameSnapshot)

                // 2. \u041f\u043e\u0432\u043e\u0440\u043e\u0442 \u0433\u043e\u043b\u043e\u0432\u044b (\u0433\u043b\u0430\u0437\u0430 \u2014 \u0434\u043e\u0447\u0435\u0440\u043d\u0438\u0435 \u043c\u0435\u0448\u0438, \u0432\u0440\u0430\u0449\u0430\u044e\u0442\u0441\u044f \u0432\u043c\u0435\u0441\u0442\u0435)
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
                    centerOrigin  = Position(0f, 0f, 0f),
                    autoAnimate   = false,
                )
            }
        }

        // \u2500\u2500 \u0418\u043d\u0434\u0438\u043a\u0430\u0442\u043e\u0440 \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0438 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        if (modelInstance == null || !materialsReady) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White.copy(alpha = 0.35f),
            )
        }

        // \u2500\u2500 \u041a\u043d\u043e\u043f\u043a\u0430 \u043f\u0435\u0440\u0435\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u044f \u0430\u0432\u0430\u0442\u0430\u0440\u0430 \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
        IconButton(
            onClick = { avatarIndex = if (avatarIndex == 1) 2 else 1 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(48.dp)
                .background(color = Color.White.copy(alpha = 0.15f), shape = CircleShape),
        ) {
            Text(
                text     = if (avatarIndex == 1) "\u2640" else "\u2642",
                color    = Color.White,
                fontSize = TextUnit(22f, TextUnitType.Sp),
            )
        }
    }
}

// \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
//  MORPH APPLICATION
// \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550

private fun applyMorphWeights(
    engine:   com.google.android.filament.Engine,
    instance: ModelInstance,
    state:    ZeroAllocRenderState,
) {
    val rm   = engine.renderableManager
    val head = state.morphWeights

    val teethW = FloatArray(5) { i -> head[ARKit.TEETH_SOURCE_INDICES[i]] }
    val eyeLW  = FloatArray(4) { i -> head[ARKit.EYE_SOURCE_INDICES[i]] }
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

// \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
//  HEAD ROTATION  (zero-alloc)
// \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550

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

    // R = Ry \u00d7 Rx \u00d7 Rz  (column-major)
    val r00 =  cy*cr + sy2*sp*sr;  val r01 = cp*sr;  val r02 = -sy2*cr + cy*sp*sr
    val r10 = -cy*sr + sy2*sp*cr;  val r11 = cp*cr;  val r12 =  sy2*sr + cy*sp*cr
    val r20 =  sy2*cp;             val r21 = -sp;    val r22 =  cy*cp

    mat[0] = r00*sx;  mat[1] = r10*sx;  mat[2]  = r20*sx;  mat[3]  = 0f
    mat[4] = r01*sy;  mat[5] = r11*sy;  mat[6]  = r21*sy;  mat[7]  = 0f
    mat[8] = r02*sz;  mat[9] = r12*sz;  mat[10] = r22*sz;  mat[11] = 0f
    mat[12] = tx;     mat[13] = ty;     mat[14] = tz;       mat[15] = 1f

    tm.setTransform(ti, mat)
}

// \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
//  MESH IDENTIFICATION
// \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550

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

// \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550
//  TEXTURE HELPERS
// \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550

private fun loadTexture(
    ctx:    android.content.Context,
    engine: com.google.android.filament.Engine,
    path:   String,
    mipmap: Boolean = true,
): Texture? = try {
    val bmp = ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } ?: return null

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
    Log.d(TAG, "Texture loaded: $path (${bmp.width}\u00d7${bmp.height}, mips=$mipLevels)")
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
    } catch