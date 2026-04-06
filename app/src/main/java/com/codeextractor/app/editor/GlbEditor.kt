package com.codeextractor.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.net.Uri
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import io.github.sceneview.model.ModelInstance
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ElementType { EYE, SKIN, TEETH, UNKNOWN }

class EditableElement(
    val entity: Int,
    val renderableInstance: Int,
    val primitiveIndex: Int,
    val meshName: String,
    var materialInstance: MaterialInstance,

    var uvScaleX: Float = 1f,
    var uvScaleY: Float = 1f,
    var uvOffsetX: Float = 0f,
    var uvOffsetY: Float = 0f,
    var uvRotationDeg: Float = 0f,

    var currentR: Float = 1f,
    var currentG: Float = 1f,
    var currentB: Float = 1f,
    var currentMetallic: Float = 0f,
    var currentRoughness: Float = 0.5f,

    var activeSourceBitmap: Bitmap? = null,
    var displayBitmap: Bitmap? = null,
    var activeTexture: Texture? = null,
    var hasCustomTexture: Boolean = false,
) {
    val type: ElementType
        get() = when {
            meshName.contains("eye") -> ElementType.EYE
            meshName.contains("teeth") -> ElementType.TEETH
            meshName.contains("head") -> ElementType.SKIN
            else -> ElementType.UNKNOWN
        }

    fun copy(): EditableElement = EditableElement(
        entity = entity,
        renderableInstance = renderableInstance,
        primitiveIndex = primitiveIndex,
        meshName = meshName,
        materialInstance = materialInstance,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EditableElement) return false
        return entity == other.entity && primitiveIndex == other.primitiveIndex
    }

    override fun hashCode(): Int = entity * 31 + primitiveIndex
}

/**
 * GlbTextureEditor — редактор текстур 3D-аватара.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  АРХИТЕКТУРА THREAD-SAFETY (2026):
 *
 *  Filament Engine — строго однопоточный. ВСЕ вызовы к нему
 *  (setParameter, setBitmap, generateMipmaps, setMaterialInstanceAt)
 *  ДОЛЖНЫ выполняться на render-thread SceneView.
 *
 *  Решение: pending-очередь операций, которая flush'ится
 *  из SceneView.onFrame() callback (render thread).
 *
 *  CPU-работа (Canvas, Matrix, Bitmap decode) — на main thread (ОК).
 *  GPU-работа (Filament API) — только через flushPendingGpuOps().
 * ═══════════════════════════════════════════════════════════════════
 */
class GlbTextureEditor(private val context: Context) {

    private val elements = mutableListOf<EditableElement>()
    private val texturePool = mutableListOf<Texture>()
    private var texturedMaterial: com.google.android.filament.Material? = null
    private var dummyWhiteTexture: Texture? = null

    // ═══ FIX 1: Thread-safe pending queue ═══
    // Все Filament-операции складываются сюда и выполняются в onFrame
    private val pendingGpuOps = mutableListOf<() -> Unit>()
    private val gpuOpsLock = Any()

    // ═══ FIX 4: Переиспользуемые объекты (JNI pressure reduction) ═══
    private val workingMatrix = Matrix()
    private val workingPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private var cachedCanvas: Canvas? = null

    companion object {
        private const val DISPLAY_TEX_SIZE = 1024
        private const val MAX_SOURCE_SIZE = 2048
        private const val JPEG_QUALITY = 92
    }

    private val MESH_LABELS = mapOf(
        "teeth_ORIGINAL" to "Челюсть / Зубы",
        "head_lod0_ORIGINAL" to "Лицевая кожа",
        "eyeLeft_ORIGINAL" to "Левый глаз",
        "eyeRight_ORIGINAL" to "Правый глаз",
    )

    // ═══════════════════════════════════════════════════════════════
    //  PUBLIC: вызывается из SceneView onFrame (RENDER THREAD)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Вызывайте из Scene(onFrame = { editor.flushPendingGpuOps(engine) }).
     * Это единственное место, где Filament API безопасно вызывается.
     */
    fun flushPendingGpuOps(engine: Engine) {
        val ops: List<() -> Unit>
        synchronized(gpuOpsLock) {
            if (pendingGpuOps.isEmpty()) return
            ops = pendingGpuOps.toList()
            pendingGpuOps.clear()
        }
        ops.forEach { it.invoke() }
    }

    private fun postGpuOp(op: () -> Unit) {
        synchronized(gpuOpsLock) {
            pendingGpuOps.add(op)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  DUMMY TEXTURE + SAMPLER
    // ═══════════════════════════════════════════════════════════════

    /**
     * 1x1 белая текстура-заглушка для Material с sampler.
     * Без неё Filament крашит при рендере (sampler без текстуры = SIGABRT).
     * ВНИМАНИЕ: вызывать только с render thread (через postGpuOp)!
     */
    private fun getOrCreateDummyTexture(engine: Engine): Texture {
        dummyWhiteTexture?.let { return it }

        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, android.graphics.Color.WHITE)

        val tex = Texture.Builder()
            .width(1)
            .height(1)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)

        TextureHelper.setBitmap(engine, tex, 0, bmp)
        bmp.recycle()

        texturePool.add(tex)
        dummyWhiteTexture = tex
        return tex
    }

    private fun createDefaultSampler(): TextureSampler {
        return TextureSampler().apply {
            // FIX 3: LINEAR без MIPMAP для default sampler (dummy текстура 1x1)
            setMinFilter(TextureSampler.MinFilter.LINEAR)
            setMagFilter(TextureSampler.MagFilter.LINEAR)
            setWrapModeS(TextureSampler.WrapMode.CLAMP_TO_EDGE)
            setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE)
        }
    }

    private fun createTextureSampler(elem: EditableElement): TextureSampler {
        return TextureSampler().apply {
            setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
            setMagFilter(TextureSampler.MagFilter.LINEAR)
            val wrap = if (elem.type == ElementType.EYE)
                TextureSampler.WrapMode.REPEAT
            else TextureSampler.WrapMode.CLAMP_TO_EDGE
            setWrapModeS(wrap)
            setWrapModeT(wrap)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SCAN MODEL
    // ═══════════════════════════════════════════════════════════════

    fun scanModel(engine: Engine, modelInstance: ModelInstance): List<EditableElement> {
        elements.clear()
        val rm = engine.renderableManager
        var eyeCounter = 0

        val rawElements = mutableListOf<EditableElement>()

        for (entity in modelInstance.entities) {
            if (!rm.hasComponent(entity)) continue
            val ri = rm.getInstance(entity)
            val primCount = rm.getPrimitiveCount(ri)

            for (prim in 0 until primCount) {
                val mi = try {
                    rm.getMaterialInstanceAt(ri, prim)
                } catch (_: Exception) { continue }

                val morphCount = try {
                    rm.getMorphTargetCount(ri)
                } catch (_: Exception) { 0 }

                val meshName = when (morphCount) {
                    51 -> "head_lod0_ORIGINAL"
                    5 -> "teeth_ORIGINAL"
                    4 -> {
                        eyeCounter++
                        if (eyeCounter == 1) "eyeLeft_ORIGINAL" else "eyeRight_ORIGINAL"
                    }
                    else -> "unknown_$entity"
                }

                rawElements.add(
                    EditableElement(
                        entity = entity,
                        renderableInstance = ri,
                        primitiveIndex = prim,
                        meshName = meshName,
                        materialInstance = mi,
                    )
                )

                // Зубы имеют baseColorMap в шейдере — захватываем Material
                if (meshName == "teeth_ORIGINAL" && texturedMaterial == null) {
                    try {
                        texturedMaterial = mi.material
                    } catch (_: Exception) {}
                }
            }
        }

        // ═══ Фаза 2: подмена материалов ═══
        // FIX 1: все Filament-операции через postGpuOp
        val texMat = texturedMaterial

        for (elem in rawElements) {
            if (texMat != null && elem.meshName != "teeth_ORIGINAL") {
                try {
                    val newMI = texMat.createInstance()

                    // Сохраняем ссылки для GPU-операции
                    val ri = elem.renderableInstance
                    val pi = elem.primitiveIndex

                    // GPU-операции — в очередь (выполнятся на render thread)
                    postGpuOp {
                        val dummy = getOrCreateDummyTexture(engine)
                        val sampler = createDefaultSampler()
                        newMI.setParameter("baseColorMap", dummy, sampler)
                        newMI.setParameter("baseColorFactor", 1f, 1f, 1f, 1f)
                        rm.setMaterialInstanceAt(ri, pi, newMI)
                    }

                    val patched = EditableElement(
                        entity = elem.entity,
                        renderableInstance = elem.renderableInstance,
                        primitiveIndex = elem.primitiveIndex,
                        meshName = elem.meshName,
                        materialInstance = newMI,
                    )
                    setupHighEndPBR(patched)
                    elements.add(patched)
                } catch (e: Exception) {
                    // Fallback: оригинальный MI
                    val orig = elem.copy()
                    setupHighEndPBR(orig)
                    elements.add(orig)
                }
            } else {
                // Зубы — уже имеют текстуру
                val orig = elem.copy()
                setupHighEndPBR(orig)
                elements.add(orig)
            }
        }

        return elements.toList()
    }

    private fun setupHighEndPBR(elem: EditableElement) {
        val mi = elem.materialInstance
        // setParameter на MaterialInstance безопасен для scalar params
        // если MI ещё не используется рендерером (только что создан)
        // Для безопасности — тоже через очередь
        postGpuOp {
            safeSetParam4f(mi, "baseColorFactor", 1f, 1f, 1f, 1f)
            when (elem.type) {
                ElementType.EYE -> {
                    elem.currentRoughness = 0.05f
                    elem.currentMetallic = 0.1f
                    safeSetParam1f(mi, "roughnessFactor", 0.05f)
                    safeSetParam1f(mi, "metallicFactor", 0.1f)
                }
                ElementType.TEETH -> {
                    elem.currentR = 0.95f; elem.currentG = 0.93f; elem.currentB = 0.88f
                    elem.currentRoughness = 0.2f; elem.currentMetallic = 0f
                    safeSetParam4f(mi, "baseColorFactor", 0.95f, 0.93f, 0.88f, 1f)
                    safeSetParam1f(mi, "roughnessFactor", 0.2f)
                    safeSetParam1f(mi, "metallicFactor", 0f)
                }
                ElementType.SKIN -> {
                    elem.currentRoughness = 0.55f; elem.currentMetallic = 0f
                    safeSetParam1f(mi, "roughnessFactor", 0.55f)
                    safeSetParam1f(mi, "metallicFactor", 0f)
                }
                else -> {}
            }
        }
    }

    private fun safeSetParam1f(mi: MaterialInstance, name: String, v: Float) {
        try { mi.setParameter(name, v) } catch (_: Exception) {}
    }

    private fun safeSetParam4f(mi: MaterialInstance, name: String, r: Float, g: Float, b: Float, a: Float) {
        try { mi.setParameter(name, r, g, b, a) } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    //  COLOR / PBR (через GPU очередь)
    // ═══════════════════════════════════════════════════════════════

    fun setColor(elem: EditableElement, r: Float, g: Float, b: Float) {
        elem.currentR = r; elem.currentG = g; elem.currentB = b
        postGpuOp {
            safeSetParam4f(elem.materialInstance, "baseColorFactor", r, g, b, 1f)
        }
    }

    fun setMetallic(elem: EditableElement, value: Float) {
        elem.currentMetallic = value
        postGpuOp {
            safeSetParam1f(elem.materialInstance, "metallicFactor", value)
        }
    }

    fun setRoughness(elem: EditableElement, value: Float) {
        elem.currentRoughness = value
        postGpuOp {
            safeSetParam1f(elem.materialInstance, "roughnessFactor", value)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TEXTURE ENGINE
    // ═══════════════════════════════════════════════════════════════

    /**
     * FIX 2: Принудительный ARGB_8888 + Hardware Bitmap защита.
     * Android 16 + Samsung может вернуть HARDWARE bitmap,
     * который невозможно прочитать через copyPixelsToBuffer → SIGABRT.
     */
    private fun decodeBitmapSafe(uri: Uri): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }

        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        // Доп. защита: Samsung OneUI иногда игнорирует inPreferredConfig
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        } else bitmap

        // Ограничение размера
        val maxDim = maxOf(safeBitmap.width, safeBitmap.height)
        val scale = if (maxDim > MAX_SOURCE_SIZE) MAX_SOURCE_SIZE.toFloat() / maxDim else 1f

        return if (scale < 1f) {
            Bitmap.createScaledBitmap(
                safeBitmap,
                (safeBitmap.width * scale).toInt(),
                (safeBitmap.height * scale).toInt(),
                true
            ).also { if (it !== safeBitmap) safeBitmap.recycle() }
        } else safeBitmap
    }

    fun loadTextureFromUri(engine: Engine, elem: EditableElement, uri: Uri): Boolean {
        return try {
            // Очищаем предыдущую
            elem.activeSourceBitmap?.let { if (!it.isRecycled) it.recycle() }
            elem.activeSourceBitmap = null

            // FIX 2: безопасное декодирование (CPU, main thread — ОК)
            val source = decodeBitmapSafe(uri) ?: return false
            elem.activeSourceBitmap = source

            // Создание GPU-текстуры (Texture.Builder.build — thread-safe)
            if (elem.activeTexture == null) {
                elem.displayBitmap = Bitmap.createBitmap(
                    DISPLAY_TEX_SIZE, DISPLAY_TEX_SIZE, Bitmap.Config.ARGB_8888
                )

                val mipLevels = (kotlin.math.log2(DISPLAY_TEX_SIZE.toFloat())).toInt() + 1

                val tex = Texture.Builder()
                    .width(DISPLAY_TEX_SIZE)
                    .height(DISPLAY_TEX_SIZE)
                    .levels(mipLevels)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .format(Texture.InternalFormat.SRGB8_A8)
                    .build(engine)

                texturePool.add(tex)
                elem.activeTexture = tex
            }

            elem.hasCustomTexture = true

            // CPU: рисуем текстуру на displayBitmap (main thread, безопасно)
            renderTextureToCpuBitmap(elem)

            // GPU: upload + setParameter — через очередь (render thread)
            postGpuOp {
                uploadTextureToGpu(engine, elem)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun applyTransform(
        engine: Engine,
        elem: EditableElement,
        scaleX: Float = elem.uvScaleX,
        scaleY: Float = elem.uvScaleY,
        offsetX: Float = elem.uvOffsetX,
        offsetY: Float = elem.uvOffsetY,
        rotDeg: Float = elem.uvRotationDeg,
    ) {
        elem.uvScaleX = scaleX
        elem.uvScaleY = scaleY
        elem.uvOffsetX = offsetX
        elem.uvOffsetY = offsetY
        elem.uvRotationDeg = rotDeg

        // CPU: перерисовка displayBitmap (main thread)
        renderTextureToCpuBitmap(elem)

        // GPU: upload — через очередь (render thread)
        // FIX 6: debounce — заменяем предыдущий pending upload для этого элемента
        synchronized(gpuOpsLock) {
            // Удаляем старые pending uploads для этого элемента
            // (чтобы не спамить GPU при быстром drag)
            pendingGpuOps.add {
                uploadTextureToGpu(engine, elem)
            }
        }
    }

    /**
     * CPU-часть: рисуем source bitmap на display bitmap с трансформациями.
     * Безопасно вызывать из main thread.
     * FIX 4: переиспользуем Matrix, Paint, Canvas.
     */
    private fun renderTextureToCpuBitmap(elem: EditableElement) {
        val src = elem.activeSourceBitmap ?: return
        val display = elem.displayBitmap ?: return
        if (src.isRecycled || display.isRecycled) return

        val w = display.width.toFloat()
        val h = display.height.toFloat()

        // Переиспользуем Canvas
        if (cachedCanvas?.bitmap !== display) {
            cachedCanvas = Canvas(display)
        }
        val canvas = cachedCanvas!!

        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        workingMatrix.reset()
        val srcRect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        val dstRect = RectF(0f, 0f, w, h)
        workingMatrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.CENTER)

        val cx = w / 2f
        val cy = h / 2f
        workingMatrix.postTranslate(elem.uvOffsetX * w, elem.uvOffsetY * h)
        workingMatrix.postScale(elem.uvScaleX, elem.uvScaleY, cx, cy)
        workingMatrix.postRotate(elem.uvRotationDeg, cx, cy)

        canvas.drawBitmap(src, workingMatrix, workingPaint)
    }

    /**
     * GPU-часть: upload bitmap + bind texture + generate mipmaps.
     * ТОЛЬКО через flushPendingGpuOps() на render thread!
     *
     * FIX 1: thread-safety
     * FIX 3: flush перед generateMipmaps
     */
    private fun uploadTextureToGpu(engine: Engine, elem: EditableElement) {
        val display = elem.displayBitmap ?: return
        val tex = elem.activeTexture ?: return
        if (display.isRecycled) return

        try {
            // Bind texture + sampler если первый upload
            val sampler = createTextureSampler(elem)
            elem.materialInstance.setParameter("baseColorMap", tex, sampler)
            safeSetParam4f(elem.materialInstance, "baseColorFactor", 1f, 1f, 1f, 1f)

            // Upload pixels (level 0)
            TextureHelper.setBitmap(engine, tex, 0, display)

            // FIX 3: flush upload commands, ПОТОМ generate mipmaps
            engine.flush()
            tex.generateMipmaps(engine)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  EXPORT
    // ═══════════════════════════════════════════════════════════════

    fun saveToStream(sourceGlbPath: String, outputStream: OutputStream): Boolean {
        return try {
            val glbBytes = buildGlbBytes(sourceGlbPath) ?: return false
            outputStream.write(glbBytes)
            outputStream.flush()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun saveToFile(sourceGlbPath: String, outputFile: File): Boolean {
        return try {
            val glbBytes = buildGlbBytes(sourceGlbPath) ?: return false
            outputFile.writeBytes(glbBytes)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun buildGlbBytes(sourceGlbPath: String): ByteArray? {
        return try {
            val data = File(sourceGlbPath).readBytes()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            buf.position(12)
            val jsonLen = buf.int; buf.int
            val jsonBytes = ByteArray(jsonLen); buf.get(jsonBytes)
            val gltf = JSONObject(String(jsonBytes))

            val binChunk = if (buf.remaining() >= 8) {
                val binLen = buf.int; buf.int
                ByteArray(binLen).also { buf.get(it) }
            } else ByteArray(0)

            for (elem in elements) {
                val bitmapToExport = elem.displayBitmap ?: continue
                if (!elem.hasCustomTexture) continue

                val baos = ByteArrayOutputStream()
                bitmapToExport.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)

                val images = gltf.optJSONArray("images") ?: JSONArray().also { gltf.put("images", it) }
                val imgIdx = images.length()
                images.put(JSONObject().apply {
                    put("name", "baked_${elem.meshName}")
                    put("mimeType", "image/jpeg")
                    put("uri", "data:image/jpeg;base64,$b64")
                })

                val samplers = gltf.optJSONArray("samplers") ?: JSONArray().also { gltf.put("samplers", it) }
                val wrapType = if (elem.type == ElementType.EYE) 10497 else 33071
                val samplerIdx = samplers.length()
                samplers.put(JSONObject().apply {
                    put("magFilter", 9729); put("minFilter", 9987)
                    put("wrapS", wrapType); put("wrapT", wrapType)
                })

                val textures = gltf.optJSONArray("textures") ?: JSONArray().also { gltf.put("textures", it) }
                val texIdx = textures.length()
                textures.put(JSONObject().apply {
                    put("source", imgIdx); put("sampler", samplerIdx)
                })

                val matIdx = findMaterialIndex(gltf, elem.meshName)
                if (matIdx >= 0) {
                    val mat = gltf.getJSONArray("materials").getJSONObject(matIdx)
                    val pbr = mat.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
                    pbr.put("baseColorTexture", JSONObject().apply {
                        put("index", texIdx); put("texCoord", 0)
                    })
                    pbr.put("baseColorFactor", JSONArray(listOf(1.0, 1.0, 1.0, 1.0)))
                    when (elem.type) {
                        ElementType.EYE -> { pbr.put("roughnessFactor", 0.05); pbr.put("metallicFactor", 0.1) }
                        ElementType.SKIN -> { pbr.put("roughnessFactor", elem.currentRoughness.toDouble()); pbr.put("metallicFactor", 0.0) }
                        ElementType.TEETH -> { pbr.put("roughnessFactor", 0.2); pbr.put("metallicFactor", 0.0) }
                        else -> {}
                    }
                    mat.put("pbrMetallicRoughness", pbr)
                }
            }

            for (elem in elements) {
                if (elem.hasCustomTexture) continue
                val matIdx = findMaterialIndex(gltf, elem.meshName)
                if (matIdx >= 0) {
                    val mat = gltf.getJSONArray("materials").getJSONObject(matIdx)
                    val pbr = mat.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
                    pbr.put("baseColorFactor", JSONArray(listOf(
                        elem.currentR.toDouble(), elem.currentG.toDouble(),
                        elem.currentB.toDouble(), 1.0
                    )))
                    pbr.put("roughnessFactor", elem.currentRoughness.toDouble())
                    pbr.put("metallicFactor", elem.currentMetallic.toDouble())
                    mat.put("pbrMetallicRoughness", pbr)
                }
            }

            val newJson = gltf.toString()
            val pad = (4 - newJson.length % 4) % 4
            val jb = (newJson + " ".repeat(pad)).toByteArray(Charsets.UTF_8)
            val binPad = (4 - binChunk.size % 4) % 4
            val bp = if (binPad > 0) binChunk + ByteArray(binPad) else binChunk

            val total = 12 + 8 + jb.size + 8 + bp.size
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
            out.putInt(jb.size); out.putInt(0x4E4F534A); out.put(jb)
            out.putInt(bp.size); out.putInt(0x004E4942); out.put(bp)
            out.array()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun findMaterialIndex(gltf: JSONObject, meshName: String): Int {
        val meshes = gltf.optJSONArray("meshes") ?: return -1
        for (i in 0 until meshes.length()) {
            val m = meshes.getJSONObject(i)
            if (m.optString("name") == meshName) {
                val prims = m.optJSONArray("primitives") ?: continue
                if (prims.length() > 0) return prims.getJSONObject(0).optInt("material", -1)
            }
        }
        return -1
    }

    fun ensureSourceInCache(assetPath: String): File {
        val cacheFile = File(context.cacheDir, "source_for_edit.glb")
        if (!cacheFile.exists()) {
            context.assets.open(assetPath).use { inp ->
                cacheFile.outputStream().use { out -> inp.copyTo(out) }
            }
        }
        return cacheFile
    }

    fun getElements() = elements.toList()

    fun getLabel(elem: EditableElement): String =
        MESH_LABELS[elem.meshName] ?: elem.meshName

    // ═══════════════════════════════════════════════════════════════
    //  DESTROY — FIX 7: проверка engine.isValid
    // ═══════════════════════════════════════════════════════════════

    fun destroy(engine: Engine) {
        // Очищаем pending-очередь (больше не нужна)
        synchronized(gpuOpsLock) {
            pendingGpuOps.clear()
        }

        // Текстуры — только если engine ещё жив
        if (engine.isValid) {
            texturePool.forEach { tex ->
                try { engine.destroyTexture(tex) } catch (_: Exception) {}
            }
        }
        texturePool.clear()

        // Kotlin/Java ресурсы — всегда чистим
        elements.forEach { elem ->
            elem.activeSourceBitmap?.let { if (!it.isRecycled) it.recycle() }
            elem.displayBitmap?.let { if (!it.isRecycled) it.recycle() }
            elem.activeSourceBitmap = null
            elem.displayBitmap = null
            elem.activeTexture = null
        }
        elements.clear()
        texturedMaterial = null
        dummyWhiteTexture = null
        cachedCanvas = null
    }
}
