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
import android.util.Log
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
 * АРХИТЕКТУРА:
 * 1. preparePatchedModel() — патчит GLB: добавляет 4x4 белую PNG
 *    как baseColorTexture через bufferView (НЕ data URI!) ко ВСЕМ
 *    материалам без текстуры. gltfio загружает их нативно.
 *
 * 2. scanModel() — читает существующие MaterialInstance,
 *    они УЖЕ имеют baseColorMap.
 *
 * 3. Thread-safety: Filament-вызовы через pending-очередь,
 *    flush из LaunchedEffect каждые 16мс.
 *
 * 4. Hardware Bitmap защита для Android 16 Samsung.
 *
 * 5. Texture.Builder с GEN_MIPMAPPABLE usage flag.
 */
class GlbTextureEditor(private val context: Context) {

    private val elements = mutableListOf<EditableElement>()
    private val texturePool = mutableListOf<Texture>()

    private val pendingGpuOps = mutableListOf<() -> Unit>()
    private val gpuOpsLock = Any()

    // ← FIX #4: переиспользуемые объекты (JNI pressure)
    private val workingMatrix = Matrix()
    private val workingPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private var cachedCanvas: Canvas? = null
    private var cachedCanvasBitmap: Bitmap? = null

    companion object {
        private const val TAG = "GLB_EDITOR"
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
    //  ПАТЧ GLB — добавляем dummy-текстуры ДО загрузки в SceneView
    //
    //  ← FIX #2: используем bufferView вместо data URI.
    //  Logcat показал:
    //    E Filament: Missing texture provider for image/
    //    E Filament: Missing texture provider for image\/png
    //  gltfio не поддерживает data URI — нужен bufferView в BIN chunk.
    // ═══════════════════════════════════════════════════════════════

    /**
     * Создаёт минимальный белый PNG (4x4 пикселя) в байтах.
     */
    private fun createWhitePngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    /**
     * Патчит GLB: добавляет 4x4 белую PNG как baseColorTexture
     * ко всем материалам без текстуры. Текстура записывается в BIN chunk
     * как bufferView (стандартный glTF embedded формат).
     *
     * Вызывать ПЕРЕД загрузкой модели в SceneView!
     */
    fun preparePatchedModel(assetPath: String): String {
        val patchedFile = File(context.cacheDir, "patched_model.glb")

        // ← ВРЕМЕННО: форсируем перегенерацию для отладки
        if (patchedFile.exists()) {
            patchedFile.delete()
            Log.d(TAG, "Cache cleared, regenerating patched model")
        }

        Log.d(TAG, "=== START preparePatchedModel ===")

        try {
            val data = context.assets.open(assetPath).use { it.readBytes() }
            Log.d(TAG, "Original GLB: ${data.size} bytes")

            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            buf.int // magic
            buf.int // version
            buf.int // total length

            val jsonLen = buf.int
            buf.int // chunk type JSON
            val jsonBytes = ByteArray(jsonLen)
            buf.get(jsonBytes)
            val gltf = JSONObject(String(jsonBytes))

            Log.d(TAG, "JSON chunk: $jsonLen bytes")

            val binChunk = if (buf.remaining() >= 8) {
                val binLen = buf.int
                buf.int // chunk type BIN
                ByteArray(binLen).also { buf.get(it) }
            } else ByteArray(0)

            Log.d(TAG, "BIN chunk: ${binChunk.size} bytes")

            // ── FIX #2: Создаём PNG и записываем в BIN chunk как bufferView ──
            val whitePng = createWhitePngBytes()
            Log.d(TAG, "White PNG size: ${whitePng.size} bytes")

            // Выравниваем смещение до 4 байт
            val pngOffset = binChunk.size
            val pngPadding = (4 - whitePng.size % 4) % 4
            val paddedPng = whitePng + ByteArray(pngPadding)

            // Новый BIN = старый BIN + PNG (с паддингом)
            val newBinChunk = binChunk + paddedPng

            // Добавляем bufferView для PNG
            val bufferViews = gltf.optJSONArray("bufferViews")
                ?: JSONArray().also { gltf.put("bufferViews", it) }
            val bvIdx = bufferViews.length()
            bufferViews.put(JSONObject().apply {
                put("buffer", 0)
                put("byteOffset", pngOffset)
                put("byteLength", whitePng.size)
            })
            Log.d(TAG, "Added bufferView[$bvIdx] at offset $pngOffset, size=${whitePng.size}")

            // Обновляем длину buffer[0]
            val buffers = gltf.optJSONArray("buffers")
                ?: JSONArray().also { gltf.put("buffers", it) }
            if (buffers.length() > 0) {
                buffers.getJSONObject(0).put("byteLength", newBinChunk.size)
            } else {
                buffers.put(JSONObject().apply {
                    put("byteLength", newBinChunk.size)
                })
            }

            // Image ссылается на bufferView (НЕ data URI!)
            val images = gltf.optJSONArray("images")
                ?: JSONArray().also { gltf.put("images", it) }
            val dummyImageIdx = images.length()
            images.put(JSONObject().apply {
                put("name", "dummy_white_4x4")
                put("mimeType", "image/png")
                put("bufferView", bvIdx) // ← КЛЮЧЕВОЕ: bufferView, а не uri
            })
            Log.d(TAG, "Added image[$dummyImageIdx] with bufferView=$bvIdx")

            // Sampler
            val samplers = gltf.optJSONArray("samplers")
                ?: JSONArray().also { gltf.put("samplers", it) }
            val dummySamplerIdx = samplers.length()
            samplers.put(JSONObject().apply {
                put("magFilter", 9729)  // LINEAR
                put("minFilter", 9729)  // LINEAR
                put("wrapS", 33071)     // CLAMP_TO_EDGE
                put("wrapT", 33071)
            })

            // Texture
            val textures = gltf.optJSONArray("textures")
                ?: JSONArray().also { gltf.put("textures", it) }
            val dummyTexIdx = textures.length()
            textures.put(JSONObject().apply {
                put("source", dummyImageIdx)
                put("sampler", dummySamplerIdx)
            })

            // Патчим ВСЕ материалы без baseColorTexture
            val materials = gltf.optJSONArray("materials")
            val matCount = materials?.length() ?: 0
            Log.d(TAG, "Materials count: $matCount")

            if (materials != null) {
                for (i in 0 until materials.length()) {
                    val mat = materials.getJSONObject(i)
                    val pbr = mat.optJSONObject("pbrMetallicRoughness")
                        ?: JSONObject().also { mat.put("pbrMetallicRoughness", it) }

                    if (!pbr.has("baseColorTexture")) {
                        pbr.put("baseColorTexture", JSONObject().apply {
                            put("index", dummyTexIdx)
                            put("texCoord", 0)
                        })
                        if (!pbr.has("baseColorFactor")) {
                            pbr.put("baseColorFactor", JSONArray(listOf(1.0, 1.0, 1.0, 1.0)))
                        }
                        Log.d(TAG, "Patching material[$i]: adding baseColorTexture")
                    } else {
                        Log.d(TAG, "Material[$i]: already has baseColorTexture, skip")
                    }
                }
            }

            // Собираем GLB с НОВЫМ BIN chunk
            val newJson = gltf.toString()
            val jsonPad = (4 - newJson.length % 4) % 4
            val jb = (newJson + " ".repeat(jsonPad)).toByteArray(Charsets.UTF_8)
            val binPad2 = (4 - newBinChunk.size % 4) % 4
            val bp = if (binPad2 > 0) newBinChunk + ByteArray(binPad2) else newBinChunk

            val total = 12 + 8 + jb.size + 8 + bp.size
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
            out.putInt(jb.size); out.putInt(0x4E4F534A); out.put(jb)
            out.putInt(bp.size); out.putInt(0x004E4942); out.put(bp)

            patchedFile.writeBytes(out.array())
            Log.d(TAG, "Patched GLB written: ${patchedFile.length()} bytes")
            Log.d(TAG, "=== END preparePatchedModel OK ===")

        } catch (e: Exception) {
            Log.e(TAG, "preparePatchedModel FAILED", e)
            // Fallback: копируем оригинал
            context.assets.open(assetPath).use { inp ->
                patchedFile.outputStream().use { out -> inp.copyTo(out) }
            }
        }

        return patchedFile.absolutePath
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU QUEUE
    // ═══════════════════════════════════════════════════════════════

    fun flushPendingGpuOps(engine: Engine) {
        val ops: List<() -> Unit>
        synchronized(gpuOpsLock) {
            if (pendingGpuOps.isEmpty()) return
            ops = pendingGpuOps.toList()
            pendingGpuOps.clear()
        }
        Log.d(TAG, "flushGpuOps: executing ${ops.size} ops")
        ops.forEachIndexed { idx, op ->
            try {
                op.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "GPU op[$idx] FAILED", e)
            }
        }
    }

    private fun postGpuOp(op: () -> Unit) {
        synchronized(gpuOpsLock) {
            pendingGpuOps.add(op)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SCAN — БЕЗ подмены материалов
    // ═══════════════════════════════════════════════════════════════

    fun scanModel(engine: Engine, modelInstance: ModelInstance): List<EditableElement> {
        Log.d(TAG, "=== START scanModel ===")
        Log.d(TAG, "Entity count: ${modelInstance.entities.size}")
        elements.clear()
        val rm = engine.renderableManager
        var eyeCounter = 0

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

                Log.d(TAG, "Entity $entity: primCount=$primCount, morphCount=$morphCount -> $meshName")

                val hasBaseColorMap = try {
                    mi.material.hasParameter("baseColorMap")
                } catch (e: Exception) {
                    Log.w(TAG, "  Cannot check baseColorMap: ${e.message}")
                    false
                }
                Log.d(TAG, "  MI hashCode=${mi.hashCode()}, hasBaseColorMap=$hasBaseColorMap")

                val elem = EditableElement(
                    entity = entity,
                    renderableInstance = ri,
                    primitiveIndex = prim,
                    meshName = meshName,
                    materialInstance = mi,
                )
                setupHighEndPBR(elem)
                elements.add(elem)
            }
        }

        Log.d(TAG, "=== END scanModel: ${elements.size} elements ===")
        return elements.toList()
    }

    private fun setupHighEndPBR(elem: EditableElement) {
        val mi = elem.materialInstance
        postGpuOp {
            Log.d(TAG, "setupHighEndPBR: ${elem.meshName} type=${elem.type}")
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
        try { mi.setParameter(name, v) } catch (e: Exception) {
            Log.w(TAG, "setParam1f($name) failed: ${e.message}")
        }
    }

    private fun safeSetParam4f(mi: MaterialInstance, name: String, r: Float, g: Float, b: Float, a: Float) {
        try { mi.setParameter(name, r, g, b, a) } catch (e: Exception) {
            Log.w(TAG, "setParam4f($name) failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  COLOR / PBR
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
    //  TEXTURE
    // ═══════════════════════════════════════════════════════════════

    // ← FIX #3: Hardware Bitmap защита для Android 16 Samsung
    private fun decodeBitmapSafe(uri: Uri): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        Log.d(TAG, "Bitmap decoded: ${bitmap.width}x${bitmap.height} config=${bitmap.config}")

        // Fallback если Samsung всё равно вернул HARDWARE
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            Log.w(TAG, "Got HARDWARE bitmap, converting to ARGB_8888")
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        } else bitmap

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
        Log.d(TAG, "loadTextureFromUri: ${elem.meshName}, uri=$uri")
        return try {
            elem.activeSourceBitmap?.let { if (!it.isRecycled) it.recycle() }
            elem.activeSourceBitmap = null

            val source = decodeBitmapSafe(uri) ?: return false
            elem.activeSourceBitmap = source
            Log.d(TAG, "Source bitmap: ${source.width}x${source.height} config=${source.config}")

            if (elem.activeTexture == null) {
                elem.displayBitmap = Bitmap.createBitmap(
                    DISPLAY_TEX_SIZE, DISPLAY_TEX_SIZE, Bitmap.Config.ARGB_8888
                )
                val mipLevels = (kotlin.math.log2(DISPLAY_TEX_SIZE.toFloat())).toInt() + 1

                // ← FIX #1: ГЛАВНЫЙ КРАШ — добавляем usage с GEN_MIPMAPPABLE
                // Logcat показал:
                //   E Filament: Precondition
                //   E Filament: in generateMipmaps:767
                //   E Filament: reason: Texture usage does not have GEN_MIPMAPPABLE set
                val tex = Texture.Builder()
                    .width(DISPLAY_TEX_SIZE)
                    .height(DISPLAY_TEX_SIZE)
                    .levels(mipLevels)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .format(Texture.InternalFormat.SRGB8_A8)
                    .usage(0x161) // SAMPLEABLE | COLOR_ATTACHMENT | UPLOADABLE | GEN_MIPMAPPABLE
                    .build(engine)

                texturePool.add(tex)
                elem.activeTexture = tex
                Log.d(TAG, "Filament Texture created: ${DISPLAY_TEX_SIZE}x${DISPLAY_TEX_SIZE} with GEN_MIPMAPPABLE")
            }

            elem.hasCustomTexture = true
            renderTextureToCpuBitmap(elem)
            postGpuOp { uploadTextureToGpu(engine, elem) }
            Log.d(TAG, "Texture queued for upload OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadTextureFromUri FAILED", e)
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
        renderTextureToCpuBitmap(elem)
        postGpuOp { uploadTextureToGpu(engine, elem) }
    }

    // ← FIX #4: переиспользуемые Matrix/Paint/Canvas
    private fun renderTextureToCpuBitmap(elem: EditableElement) {
        val src = elem.activeSourceBitmap ?: return
        val display = elem.displayBitmap ?: return
        if (src.isRecycled || display.isRecycled) return

        val w = display.width.toFloat()
        val h = display.height.toFloat()

        if (cachedCanvasBitmap !== display) {
            cachedCanvas = Canvas(display)
            cachedCanvasBitmap = display
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

    private fun uploadTextureToGpu(engine: Engine, elem: EditableElement) {
        val display = elem.displayBitmap ?: return
        val tex = elem.activeTexture ?: return
        if (display.isRecycled) return

        Log.d(TAG, "uploadTextureToGpu: ${elem.meshName}")

        try {
            val sampler = TextureSampler().apply {
                setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                setMagFilter(TextureSampler.MagFilter.LINEAR)
                val wrap = if (elem.type == ElementType.EYE)
                    TextureSampler.WrapMode.REPEAT
                else TextureSampler.WrapMode.CLAMP_TO_EDGE
                setWrapModeS(wrap)
                setWrapModeT(wrap)
            }
            elem.materialInstance.setParameter("baseColorMap", tex, sampler)
            safeSetParam4f(elem.materialInstance, "baseColorFactor", 1f, 1f, 1f, 1f)
            TextureHelper.setBitmap(engine, tex, 0, display)
            tex.generateMipmaps(engine) // Теперь безопасно — текстура имеет GEN_MIPMAPPABLE
            Log.d(TAG, "uploadTextureToGpu OK: ${elem.meshName}")
        } catch (e: Exception) {
            Log.e(TAG, "uploadTextureToGpu FAILED for ${elem.meshName}", e)
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

                val imgArr = gltf.optJSONArray("images") ?: JSONArray().also { gltf.put("images", it) }
                val imgIdx = imgArr.length()
                imgArr.put(JSONObject().apply {
                    put("name", "baked_${elem.meshName}")
                    put("mimeType", "image/jpeg")
                    put("uri", "data:image/jpeg;base64,$b64")
                })

                val sampArr = gltf.optJSONArray("samplers") ?: JSONArray().also { gltf.put("samplers", it) }
                val wrapType = if (elem.type == ElementType.EYE) 10497 else 33071
                val sampIdx = sampArr.length()
                sampArr.put(JSONObject().apply {
                    put("magFilter", 9729); put("minFilter", 9987)
                    put("wrapS", wrapType); put("wrapT", wrapType)
                })

                val texArr = gltf.optJSONArray("textures") ?: JSONArray().also { gltf.put("textures", it) }
                val texIdx = texArr.length()
                texArr.put(JSONObject().apply {
                    put("source", imgIdx); put("sampler", sampIdx)
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

    fun destroy(engine: Engine) {
        Log.d(TAG, "destroy: ${texturePool.size} textures, ${elements.size} elements")
        synchronized(gpuOpsLock) { pendingGpuOps.clear() }
        texturePool.forEach { tex ->
            try { engine.destroyTexture(tex) } catch (_: Exception) {}
        }
        texturePool.clear()
        elements.forEach { elem ->
            elem.activeSourceBitmap?.let { if (!it.isRecycled) it.recycle() }
            elem.displayBitmap?.let { if (!it.isRecycled) it.recycle() }
            elem.activeSourceBitmap = null
            elem.displayBitmap = null
            elem.activeTexture = null
        }
        elements.clear()
        cachedCanvas = null
        cachedCanvasBitmap = null
    }
}
