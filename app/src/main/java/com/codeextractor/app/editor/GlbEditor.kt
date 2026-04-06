package com.codeextractor.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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

// ═══════════════════════════════════════════════════════════════
//  Тип элемента модели
// ═══════════════════════════════════════════════════════════════
enum class ElementType { EYE, SKIN, TEETH, UNKNOWN }

// ═══════════════════════════════════════════════════════════════
//  Редактируемый элемент — хранит всё состояние трансформации
// ═══════════════════════════════════════════════════════════════
class EditableElement(
    val entity: Int,
    val renderableInstance: Int,
    val primitiveIndex: Int,
    val meshName: String,
    val materialInstance: MaterialInstance,

    // UV Transform state
    var uvScaleX: Float = 1f,
    var uvScaleY: Float = 1f,
    var uvOffsetX: Float = 0f,
    var uvOffsetY: Float = 0f,
    var uvRotationDeg: Float = 0f,

    // PBR state
    var currentR: Float = 1f,
    var currentG: Float = 1f,
    var currentB: Float = 1f,
    var currentMetallic: Float = 0f,
    var currentRoughness: Float = 0.5f,

    // Texture pipeline
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

// ═══════════════════════════════════════════════════════════════
//  MONSTER V2 PRO — 3D Texture Editor Engine (April 2026)
// ═══════════════════════════════════════════════════════════════
class GlbTextureEditor(private val context: Context) {

    private val elements = mutableListOf<EditableElement>()
    private val texturePool = mutableListOf<Texture>()

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

    // ─── Сканирование модели ────────────────────────────────
    fun scanModel(engine: Engine, modelInstance: ModelInstance): List<EditableElement> {
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
                } catch (e: Exception) {
                    continue
                }

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

                val elem = EditableElement(
                    entity = entity,
                    renderableInstance = ri,
                    primitiveIndex = prim,
                    meshName = meshName,
                    materialInstance = mi,
                )

                // Настраиваем PBR по типу элемента
                setupHighEndPBR(elem)
                elements.add(elem)
            }
        }
        return elements.toList()
    }

    // ─── PBR Setup — убиваем серый baseColor 0.503 ─────────
    private fun setupHighEndPBR(elem: EditableElement) {
        val mi = elem.materialInstance

        // Сброс грязно-серой базы на чистый белый
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

    // ─── Безопасные параметры Filament ──────────────────────
    private fun safeSetParam1f(mi: MaterialInstance, name: String, v: Float) {
        try { mi.setParameter(name, v) } catch (_: Exception) {}
    }

    private fun safeSetParam4f(mi: MaterialInstance, name: String, r: Float, g: Float, b: Float, a: Float) {
        try { mi.setParameter(name, r, g, b, a) } catch (_: Exception) {}
    }

    // ─── Публичные методы PBR ───────────────────────────────
    fun setColor(elem: EditableElement, r: Float, g: Float, b: Float) {
        elem.currentR = r; elem.currentG = g; elem.currentB = b
        safeSetParam4f(elem.materialInstance, "baseColorFactor", r, g, b, 1f)
    }

    fun setMetallic(elem: EditableElement, value: Float) {
        elem.currentMetallic = value
        safeSetParam1f(elem.materialInstance, "metallicFactor", value)
    }

    fun setRoughness(elem: EditableElement, value: Float) {
        elem.currentRoughness = value
        safeSetParam1f(elem.materialInstance, "roughnessFactor", value)
    }

    // ═══════════════════════════════════════════════════════════
    //  TEXTURE ENGINE — загрузка и трансформации (Zero-Lag)
    // ═══════════════════════════════════════════════════════════

    fun loadTextureFromUri(engine: Engine, elem: EditableElement, uri: Uri): Boolean {
        return try {
            // ── FIX: ресайклим предыдущий source bitmap ──
            elem.activeSourceBitmap?.let { old ->
                if (!old.isRecycled) old.recycle()
            }
            elem.activeSourceBitmap = null

            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return false

            val maxDim = maxOf(bitmap.width, bitmap.height)
            val scale = if (maxDim > MAX_SOURCE_SIZE) MAX_SOURCE_SIZE.toFloat() / maxDim else 1f

            val source = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                ).also { if (it !== bitmap) bitmap.recycle() }
            } else bitmap

            elem.activeSourceBitmap = source

            // Создаём display buffer и GPU текстуру (один раз на элемент)
            if (elem.activeTexture == null) {
                elem.displayBitmap = Bitmap.createBitmap(
                    DISPLAY_TEX_SIZE, DISPLAY_TEX_SIZE, Bitmap.Config.ARGB_8888
                )

                val tex = Texture.Builder()
                    .width(DISPLAY_TEX_SIZE)
                    .height(DISPLAY_TEX_SIZE)
                    .levels(0xff)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .format(Texture.InternalFormat.SRGB8_A8)
                    .build(engine)

                texturePool.add(tex)
                elem.activeTexture = tex

                // Привязываем к материалу
                val sampler = TextureSampler().apply {
                    setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                    setMagFilter(TextureSampler.MagFilter.LINEAR)
                    val wrap = if (elem.type == ElementType.EYE)
                        TextureSampler.WrapMode.REPEAT
                    else TextureSampler.WrapMode.CLAMP_TO_EDGE
                    setWrapModeS(wrap)
                    setWrapModeT(wrap)
                }

                try {
                    elem.materialInstance.setParameter("baseColorMap", tex, sampler)
                } catch (_: Exception) {}
            }

            // Сбрасываем baseColorFactor на белый чтобы текстура не тонировалась
            safeSetParam4f(elem.materialInstance, "baseColorFactor", 1f, 1f, 1f, 1f)
            elem.hasCustomTexture = true

            // Рендерим текстуру с текущими трансформациями
            renderTextureToGpu(engine, elem)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Обновить трансформацию текстуры — вызывается из UI при изменении любого параметра
     */
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
        renderTextureToGpu(engine, elem)
    }

    /**
     * Ядро: рисуем source bitmap на display buffer через Android Matrix,
     * затем вбрасываем в GPU за ~1-3ms
     */
    private fun renderTextureToGpu(engine: Engine, elem: EditableElement) {
        val src = elem.activeSourceBitmap ?: return
        val display = elem.displayBitmap ?: return
        val tex = elem.activeTexture ?: return

        val canvas = Canvas(display)
        val w = display.width.toFloat()
        val h = display.height.toFloat()

        // Очистка
        val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawPaint(clearPaint)

        // Матрица трансформации
        val matrix = Matrix()
        val srcRect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        val dstRect = RectF(0f, 0f, w, h)
        matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.CENTER)

        val cx = w / 2f
        val cy = h / 2f
        matrix.postTranslate(elem.uvOffsetX * w, elem.uvOffsetY * h)
        matrix.postScale(elem.uvScaleX, elem.uvScaleY, cx, cy)
        matrix.postRotate(elem.uvRotationDeg, cx, cy)

        val drawPaint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        }
        canvas.drawBitmap(src, matrix, drawPaint)

        // GPU upload
        try {
            TextureHelper.setBitmap(engine, tex, 0, display)
            tex.generateMipmaps(engine)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  СОХРАНЕНИЕ GLB — Pixel Baking + PBR Export
    // ═══════════════════════════════════════════════════════════

    /**
     * Сохраняет в OutputStream (для SAF Uri)
     */
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

    /**
     * Сохраняет в файл (fallback)
     */
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

            // Parse GLB header
            buf.position(12)
            val jsonLen = buf.int
            buf.int // skip chunk type
            val jsonBytes = ByteArray(jsonLen)
            buf.get(jsonBytes)
            val gltf = JSONObject(String(jsonBytes))

            // Binary chunk
            val binChunk = if (buf.remaining() >= 8) {
                val binLen = buf.int
                buf.int // skip chunk type
                ByteArray(binLen).also { buf.get(it) }
            } else ByteArray(0)

            // Запекаем каждый элемент с текстурой
            for (elem in elements) {
                val bitmapToExport = elem.displayBitmap ?: continue
                if (!elem.hasCustomTexture) continue

                // JPEG encode
                val baos = ByteArrayOutputStream()
                bitmapToExport.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                val b64 = android.util.Base64.encodeToString(
                    baos.toByteArray(), android.util.Base64.NO_WRAP
                )

                // Image
                val images = gltf.optJSONArray("images")
                    ?: JSONArray().also { gltf.put("images", it) }
                val imgIdx = images.length()
                images.put(JSONObject().apply {
                    put("name", "baked_${elem.meshName}")
                    put("mimeType", "image/jpeg")
                    put("uri", "data:image/jpeg;base64,$b64")
                })

                // Sampler — создаём отдельный для каждого типа элемента
                val samplers = gltf.optJSONArray("samplers")
                    ?: JSONArray().also { gltf.put("samplers", it) }
                val wrapType = if (elem.type == ElementType.EYE) 10497 else 33071
                // Добавляем новый sampler для этого элемента
                val samplerIndex = samplers.length()
                samplers.put(JSONObject().apply {
                    put("magFilter", 9729)  // LINEAR
                    put("minFilter", 9987)  // LINEAR_MIPMAP_LINEAR
                    put("wrapS", wrapType)
                    put("wrapT", wrapType)
                })

                // Texture reference
                val textures = gltf.optJSONArray("textures")
                    ?: JSONArray().also { gltf.put("textures", it) }
                val texIdx = textures.length()
                textures.put(JSONObject().apply {
                    put("source", imgIdx)
                    put("sampler", samplerIndex)
                })

                // Material PBR update
                val matIdx = findMaterialIndex(gltf, elem.meshName)
                if (matIdx >= 0) {
                    val mat = gltf.getJSONArray("materials").getJSONObject(matIdx)
                    val pbr = mat.optJSONObject("pbrMetallicRoughness") ?: JSONObject()

                    pbr.put("baseColorTexture", JSONObject().apply {
                        put("index", texIdx)
                        put("texCoord", 0)
                    })

                    // Белый baseColorFactor — текстура не тонируется
                    pbr.put("baseColorFactor", JSONArray(listOf(1.0, 1.0, 1.0, 1.0)))

                    // Запекаем PBR параметры
                    when (elem.type) {
                        ElementType.EYE -> {
                            pbr.put("roughnessFactor", 0.05)
                            pbr.put("metallicFactor", 0.1)
                        }
                        ElementType.SKIN -> {
                            pbr.put("roughnessFactor", elem.currentRoughness.toDouble())
                            pbr.put("metallicFactor", 0.0)
                        }
                        ElementType.TEETH -> {
                            pbr.put("roughnessFactor", 0.2)
                            pbr.put("metallicFactor", 0.0)
                        }
                        else -> {}
                    }
                    mat.put("pbrMetallicRoughness", pbr)
                }
            }

            // Также прошиваем PBR для элементов БЕЗ текстуры (чтобы серый 0.503 не вернулся)
            for (elem in elements) {
                if (elem.hasCustomTexture) continue
                val matIdx = findMaterialIndex(gltf, elem.meshName)
                if (matIdx >= 0) {
                    val mat = gltf.getJSONArray("materials").getJSONObject(matIdx)
                    val pbr = mat.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
                    pbr.put("baseColorFactor", JSONArray(listOf(
                        elem.currentR.toDouble(),
                        elem.currentG.toDouble(),
                        elem.currentB.toDouble(),
                        1.0
                    )))
                    pbr.put("roughnessFactor", elem.currentRoughness.toDouble())
                    pbr.put("metallicFactor", elem.currentMetallic.toDouble())
                    mat.put("pbrMetallicRoughness", pbr)
                }
            }

            // Собираем GLB
            val newJson = gltf.toString()
            val pad = (4 - newJson.length % 4) % 4
            val paddedJson = newJson + " ".repeat(pad)
            val jb = paddedJson.toByteArray(Charsets.UTF_8)

            val binPad = (4 - binChunk.size % 4) % 4
            val bp = if (binPad > 0) binChunk + ByteArray(binPad) else binChunk

            val total = 12 + 8 + jb.size + 8 + bp.size
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0x46546C67) // magic "glTF"
            out.putInt(2)          // version
            out.putInt(total)      // length
            out.putInt(jb.size); out.putInt(0x4E4F534A); out.put(jb) // JSON chunk
            out.putInt(bp.size); out.putInt(0x004E4942); out.put(bp) // BIN chunk

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
                if (prims.length() > 0) {
                    return prims.getJSONObject(0).optInt("material", -1)
                }
            }
        }
        return -1
    }

    // ─── Утилиты ────────────────────────────────────────────
    fun getElements() = elements.toList()

    fun getLabel(elem: EditableElement): String =
        MESH_LABELS[elem.meshName] ?: elem.meshName

    /**
     * Подготавливает source GLB файл в cache (копия из assets)
     */
    fun ensureSourceInCache(assetPath: String): File {
        val cacheFile = File(context.cacheDir, "source_for_edit.glb")
        if (!cacheFile.exists()) {
            context.assets.open(assetPath).use { inp ->
                cacheFile.outputStream().use { out -> inp.copyTo(out) }
            }
        }
        return cacheFile
    }

    fun destroy(engine: Engine) {
        texturePool.forEach { tex ->
            try { engine.destroyTexture(tex) } catch (_: Exception) {}
        }
        texturePool.clear()
        elements.forEach { elem ->
            elem.activeSourceBitmap?.recycle()
            elem.displayBitmap?.recycle()
            elem.activeSourceBitmap = null
            elem.displayBitmap = null
            elem.activeTexture = null
        }
        elements.clear()
    }
}