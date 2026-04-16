package com.learnde.app.editor

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

// ═══════════════════════════════════════════════════════════════════
//  ЗОНЫ UV-КАРТЫ для head_lod0_ORIGINAL
// ═══════════════════════════════════════════════════════════════════

enum class HeadZone(val label: String, val maskAsset: String) {
    FACE_FRONT("Лицо (перед)", "masks/face_front.png"),
    SIDE_LEFT("Бок левый", "masks/side_left.png"),
    SIDE_RIGHT("Бок правый", "masks/side_right.png"),
    HAIR_TOP("Волосы (верх)", "masks/hair_top.png"),
    HAIR_BACK("Волосы (зад)", "masks/hair_back.png"),
    NECK_FRONT("Шея (перед)", "masks/neck_front.png"),
    NECK_BACK("Шея (зад)", "masks/neck_back.png"),
    MOUTH_INNER("Рот (внутри)", "masks/mouth_inner.png"),
}

enum class ElementType { EYE_LEFT, EYE_RIGHT, TEETH, HEAD_ZONE, UNKNOWN }

class ZoneData(
    val zone: HeadZone,
    val maskBitmap: Bitmap,
) {
    var sourceBitmap: Bitmap? = null
    var uvScaleX: Float = 1f
    var uvScaleY: Float = 1f
    var uvOffsetX: Float = 0f
    var uvOffsetY: Float = 0f
    var uvRotationDeg: Float = 0f
    var hasTexture: Boolean = false

    fun recycle() {
        sourceBitmap?.let { if (!it.isRecycled) it.recycle() }
        sourceBitmap = null
    }
}

class EditableElement(
    val entity: Int,
    val renderableInstance: Int,
    val primitiveIndex: Int,
    val meshName: String,
    var materialInstance: MaterialInstance,
    val type: ElementType,
    val headZone: HeadZone? = null,

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
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EditableElement) return false
        if (type == ElementType.HEAD_ZONE && other.type == ElementType.HEAD_ZONE)
            return headZone == other.headZone
        return entity == other.entity && primitiveIndex == other.primitiveIndex
    }

    override fun hashCode(): Int {
        if (type == ElementType.HEAD_ZONE) return headZone.hashCode()
        return entity * 31 + primitiveIndex
    }
}

class GlbTextureEditor(private val context: Context) {

    private val elements = mutableListOf<EditableElement>()
    private val texturePool = mutableListOf<Texture>()
    private val pendingGpuOps = mutableListOf<() -> Unit>()
    private val gpuOpsLock = Any()

    // Отложенное уничтожение текстур: пара (texture, flushCount когда можно уничтожить)
    private val texturesToDestroy = ArrayDeque<Pair<Texture, Int>>()
    private var flushCount = 0

    // ── Зональная система головы ──
    private val zoneDataMap = mutableMapOf<HeadZone, ZoneData>()
    private var headCompositeBitmap: Bitmap? = null
    private var headCompositeTexture: Texture? = null
    private var headMaterialInstance: MaterialInstance? = null

    // ИСПРАВЛЕНО: правильный тёплый цвет кожи (был 220,187,155 = слишком светлый/кремовый)
    private var headBgColor: Int = android.graphics.Color.rgb(185, 142, 96)

    private val workMatrix = Matrix()
    private val workPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

    companion object {
        private const val TAG = "GLB_EDITOR"
        private const val TEX_SIZE = 1024
        private const val MAX_SOURCE = 2048

        // Целевой цвет кожи (sRGB) для bitmap-композитора
        val SKIN_COLOR_SRGB = android.graphics.Color.rgb(185, 142, 96)

        const val RT_HEAD = "runtime_head_texture.png"
        const val RT_EYE_L = "runtime_tex_eyeLeft.png"
        const val RT_EYE_R = "runtime_tex_eyeRight.png"
        const val RT_TEETH = "runtime_tex_teeth.png"
    }

    // ═══════════════════════════════════════════════════════════════
    //  ИНИЦИАЛИЗАЦИЯ МАСОК
    // ═══════════════════════════════════════════════════════════════

    private fun loadZoneMasks() {
        Log.d(TAG, "Loading zone masks...")
        for (zone in HeadZone.entries) {
            try {
                val bmp = context.assets.open(zone.maskAsset).use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: continue

                val scaled = if (bmp.width != TEX_SIZE || bmp.height != TEX_SIZE) {
                    Bitmap.createScaledBitmap(bmp, TEX_SIZE, TEX_SIZE, true)
                        .also { if (it !== bmp) bmp.recycle() }
                } else bmp

                zoneDataMap[zone] = ZoneData(zone, scaled)
                Log.d(TAG, "  Mask loaded: ${zone.name} (${scaled.width}x${scaled.height})")
            } catch (e: Exception) {
                Log.e(TAG, "  Failed to load mask: ${zone.maskAsset}", e)
            }
        }
        Log.d(TAG, "Zone masks loaded: ${zoneDataMap.size}/${HeadZone.entries.size}")
    }

    // ═══════════════════════════════════════════════════════════════
    //  ПАТЧ GLB
    //  ИСПРАВЛЕНО: сбрасываем metallicFactor=0 и baseColorFactor=white
    //  в GLB JSON, чтобы модель не выглядела чёрной до применения setParameter
    // ═══════════════════════════════════════════════════════════════

    private fun createWhitePngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    fun preparePatchedModel(assetPath: String): String {
        val patchedFile = File(context.cacheDir, "patched_model.glb")
        if (patchedFile.exists()) patchedFile.delete()
        Log.d(TAG, "=== preparePatchedModel ===")

        try {
            val data = context.assets.open(assetPath).use { it.readBytes() }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buf.int; buf.int; buf.int

            val jsonLen = buf.int; buf.int
            val jsonBytes = ByteArray(jsonLen); buf.get(jsonBytes)
            val gltf = JSONObject(String(jsonBytes))

            val binChunk = if (buf.remaining() >= 8) {
                val bl = buf.int; buf.int; ByteArray(bl).also { buf.get(it) }
            } else ByteArray(0)

            val whitePng = createWhitePngBytes()
            val pngOffset = binChunk.size
            val pngPadding = (4 - whitePng.size % 4) % 4
            val newBinChunk = binChunk + whitePng + ByteArray(pngPadding)

            val bufferViews = gltf.optJSONArray("bufferViews")
                ?: JSONArray().also { gltf.put("bufferViews", it) }
            val bvIdx = bufferViews.length()
            bufferViews.put(JSONObject().apply {
                put("buffer", 0); put("byteOffset", pngOffset); put("byteLength", whitePng.size)
            })

            val buffers = gltf.optJSONArray("buffers")
                ?: JSONArray().also { gltf.put("buffers", it) }
            if (buffers.length() > 0) buffers.getJSONObject(0).put("byteLength", newBinChunk.size)
            else buffers.put(JSONObject().apply { put("byteLength", newBinChunk.size) })

            val images = gltf.optJSONArray("images")
                ?: JSONArray().also { gltf.put("images", it) }
            val imgIdx = images.length()
            images.put(JSONObject().apply {
                put("name", "dummy_white"); put("mimeType", "image/png"); put("bufferView", bvIdx)
            })

            val samplers = gltf.optJSONArray("samplers")
                ?: JSONArray().also { gltf.put("samplers", it) }
            val sampIdx = samplers.length()
            samplers.put(JSONObject().apply {
                put("magFilter", 9729); put("minFilter", 9729)
                put("wrapS", 33071); put("wrapT", 33071)
            })

            val textures = gltf.optJSONArray("textures")
                ?: JSONArray().also { gltf.put("textures", it) }
            val texIdx = textures.length()
            textures.put(JSONObject().apply { put("source", imgIdx); put("sampler", sampIdx) })

            val materials = gltf.optJSONArray("materials")
            if (materials != null) {
                for (i in 0 until materials.length()) {
                    val mat = materials.getJSONObject(i)
                    val matName = mat.optString("name", "").lowercase() // Читаем имя из GLB

                    val pbr = mat.optJSONObject("pbrMetallicRoughness")
                        ?: JSONObject().also { mat.put("pbrMetallicRoughness", it) }

                    if (!pbr.has("baseColorTexture")) {
                        pbr.put("baseColorTexture", JSONObject().apply {
                            put("index", texIdx); put("texCoord", 0)
                        })
                    }

                    // 👇=== БЕЗОПАСНАЯ РАСКРАСКА ===👇
                    // Если это материал рта/полости, даем ему реалистичный тёмно-розовый цвет
                    if (matName.contains("mouth") || matName.contains("cavity") || matName.contains("tongue") || matName.contains("oral")) {
                        pbr.put("baseColorFactor", JSONArray(listOf(0.76, 0.33, 0.28, 1.0)))
                        pbr.put("roughnessFactor", 0.8)
                    } else {
                        // Для кожи, глаз и зубов оставляем чистый белый (они получат текстуры позже)
                        pbr.put("baseColorFactor", JSONArray(listOf(1.0, 1.0, 1.0, 1.0)))
                        pbr.put("roughnessFactor", 0.6)
                    }
                    
                    pbr.put("metallicFactor", 0.0)
                    // 👆===========================👆
                }
            }

            val newJson = gltf.toString()
            val jsonPad = (4 - newJson.length % 4) % 4
            val jb = (newJson + " ".repeat(jsonPad)).toByteArray(Charsets.UTF_8)
            val binPad = (4 - newBinChunk.size % 4) % 4
            val bp = if (binPad > 0) newBinChunk + ByteArray(binPad) else newBinChunk

            val total = 12 + 8 + jb.size + 8 + bp.size
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
            out.putInt(jb.size); out.putInt(0x4E4F534A); out.put(jb)
            out.putInt(bp.size); out.putInt(0x004E4942); out.put(bp)
            patchedFile.writeBytes(out.array())
            Log.d(TAG, "Patched GLB: ${patchedFile.length()} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Patch failed", e)
            context.assets.open(assetPath).use { inp ->
                patchedFile.outputStream().use { out -> inp.copyTo(out) }
            }
        }
        return patchedFile.absolutePath
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU QUEUE
    //  ИСПРАВЛЕНО: отложенное уничтожение текстур через 2 flush-цикла (~32ms)
    //  чтобы GPU успел закончить чтение старой текстуры
    // ═══════════════════════════════════════════════════════════════

    fun flushPendingGpuOps(engine: Engine) {
        flushCount++

        // Уничтожаем текстуры, которые ждали 2+ flush-цикла (GPU точно закончил их читать)
        val toDestroyNow = texturesToDestroy.filter { it.second <= flushCount }
        if (toDestroyNow.isNotEmpty()) {
            texturesToDestroy.removeAll { it.second <= flushCount }
            toDestroyNow.forEach { (tex, _) ->
                try { engine.destroyTexture(tex) } catch (_: Exception) {}
                texturePool.remove(tex)
            }
        }

        val ops: List<() -> Unit>
        synchronized(gpuOpsLock) {
            if (pendingGpuOps.isEmpty()) return
            ops = pendingGpuOps.toList()
            pendingGpuOps.clear()
        }
        ops.forEach { op ->
            try { op.invoke() } catch (e: Exception) { Log.e(TAG, "GPU op failed", e) }
        }
    }

    private fun postGpuOp(op: () -> Unit) {
        synchronized(gpuOpsLock) { pendingGpuOps.add(op) }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SCAN MODEL
    // ═══════════════════════════════════════════════════════════════

    fun scanModel(engine: Engine, modelInstance: ModelInstance): List<EditableElement> {
        Log.d(TAG, "=== scanModel ===")
        elements.clear()

        if (zoneDataMap.isEmpty()) loadZoneMasks()

        val rm = engine.renderableManager
        var eyeCounter = 0
        var headEntity = -1
        var headRI = -1
        var headMI: MaterialInstance? = null

        for (entity in modelInstance.entities) {
            if (!rm.hasComponent(entity)) continue
            val ri = rm.getInstance(entity)
            val primCount = rm.getPrimitiveCount(ri)

            for (prim in 0 until primCount) {
                val mi = try { rm.getMaterialInstanceAt(ri, prim) } catch (_: Exception) { continue }
                val morphCount = try { rm.getMorphTargetCount(ri) } catch (_: Exception) { 0 }
                val matName = mi.name?.lowercase() ?: ""

                // 1. Изолируем полость рта для редактора
                if (matName.contains("mouth") || matName.contains("cavity") || matName.contains("tongue")) {
                    postGpuOp {
                        safeSet4f(mi, "baseColorFactor", 0.76f, 0.33f, 0.28f, 1f)
                        safeSet1f(mi, "roughnessFactor", 0.8f)
                    }
                    continue // Обязательно пропускаем!
                }

                // 2. Добавляем элементы безопасно (только если это первый примитив)
                when (morphCount) {
                    51 -> {
                        if (headMI == null) { // Защита от перезаписи
                            headEntity = entity
                            headRI = ri
                            headMI = mi
                            headMaterialInstance = mi
                            Log.d(TAG, "Head mesh: entity=$entity, material=$matName")
                        }
                    }
                    5 -> {
                        if (prim == 0) { // Защита от дублирования
                            elements.add(EditableElement(
                                entity = entity, renderableInstance = ri,
                                primitiveIndex = prim, meshName = "teeth_ORIGINAL",
                                materialInstance = mi, type = ElementType.TEETH,
                            ))
                        }
                    }
                    4 -> {
                        if (prim == 0) { // Защита от дублирования
                            eyeCounter++
                            val isLeft = eyeCounter == 1
                            elements.add(EditableElement(
                                entity = entity, renderableInstance = ri,
                                primitiveIndex = prim,
                                meshName = if (isLeft) "eyeLeft_ORIGINAL" else "eyeRight_ORIGINAL",
                                materialInstance = mi,
                                type = if (isLeft) ElementType.EYE_LEFT else ElementType.EYE_RIGHT,
                            ))
                        }
                    }
                }
            }
        }

        if (headMI != null) {
            for (zone in HeadZone.entries) {
                if (zoneDataMap.containsKey(zone)) {
                    elements.add(EditableElement(
                        entity = headEntity,
                        renderableInstance = headRI,
                        primitiveIndex = 0,
                        meshName = "head_zone_${zone.name}",
                        materialInstance = headMI,
                        type = ElementType.HEAD_ZONE,
                        headZone = zone,
                    ))
                }
            }
        }

        elements.forEach { elem -> setupPBR(elem) }

        if (headMaterialInstance != null) {
            ensureHeadCompositeTexture(engine)
            compositeAndUploadHead(engine)
        }

        Log.d(TAG, "Total elements: ${elements.size}")
        return elements.toList()
    }

    private fun setupPBR(elem: EditableElement) {
        val mi = elem.materialInstance
        postGpuOp {
            when (elem.type) {
                ElementType.EYE_LEFT, ElementType.EYE_RIGHT -> {
                    elem.currentRoughness = 0.05f; elem.currentMetallic = 0f
                    safeSet1f(mi, "roughnessFactor", 0.05f)
                    safeSet1f(mi, "metallicFactor", 0f)
                    // Белки глаз — белый цвет
                    safeSet4f(mi, "baseColorFactor", 0.95f, 0.95f, 0.95f, 1f)
                }
                ElementType.TEETH -> {
                    elem.currentR = 0.95f; elem.currentG = 0.93f; elem.currentB = 0.88f
                    elem.currentRoughness = 0.2f; elem.currentMetallic = 0f
                    safeSet4f(mi, "baseColorFactor", 0.95f, 0.93f, 0.88f, 1f)
                    safeSet1f(mi, "roughnessFactor", 0.2f)
                    safeSet1f(mi, "metallicFactor", 0f)
                }
                ElementType.HEAD_ZONE -> {
                    // Применяем только для FACE_FRONT (один MI для всей головы)
                    // НО compositeAndUploadHead перекроет эти значения текстурой
                    if (elem.headZone == HeadZone.FACE_FRONT) {
                        elem.currentMetallic = 0f
                        elem.currentRoughness = 0.6f
                        safeSet1f(mi, "roughnessFactor", 0.6f)
                        safeSet1f(mi, "metallicFactor", 0f)
                    }
                }
                else -> {}
            }
        }
    }

    private fun safeSet1f(mi: MaterialInstance, name: String, v: Float) {
        try { mi.setParameter(name, v) } catch (e: Exception) {
            Log.w(TAG, "set1f($name) fail: ${e.message}")
        }
    }

    private fun safeSet4f(mi: MaterialInstance, name: String, r: Float, g: Float, b: Float, a: Float) {
        try { mi.setParameter(name, r, g, b, a) } catch (e: Exception) {
            Log.w(TAG, "set4f($name) fail: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TEXTURE LOADING
    // ═══════════════════════════════════════════════════════════════

    private fun decodeBitmapSafe(uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = false
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val safe = if (bmp.config == Bitmap.Config.HARDWARE) {
            bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
        } else bmp

        val maxDim = maxOf(safe.width, safe.height)
        val scale = if (maxDim > MAX_SOURCE) MAX_SOURCE.toFloat() / maxDim else 1f
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(safe, (safe.width * scale).toInt(), (safe.height * scale).toInt(), true)
                .also { if (it !== safe) safe.recycle() }
        } else safe
    }

    fun loadTextureForZone(engine: Engine, elem: EditableElement, uri: Uri): Boolean {
        if (elem.type != ElementType.HEAD_ZONE || elem.headZone == null) {
            return loadTextureForSeparateMesh(engine, elem, uri)
        }

        val zone = elem.headZone
        val zd = zoneDataMap[zone] ?: return false

        Log.d(TAG, "loadTextureForZone: ${zone.name}")
        return try {
            zd.recycle()
            val source = decodeBitmapSafe(uri) ?: return false
            zd.sourceBitmap = source
            zd.hasTexture = true

            zd.uvScaleX = elem.uvScaleX
            zd.uvScaleY = elem.uvScaleY
            zd.uvOffsetX = elem.uvOffsetX
            zd.uvOffsetY = elem.uvOffsetY
            zd.uvRotationDeg = elem.uvRotationDeg

            elem.hasCustomTexture = true

            ensureHeadCompositeTexture(engine)
            compositeAndUploadHead(engine)
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadTextureForZone FAILED", e)
            false
        }
    }

    private fun loadTextureForSeparateMesh(engine: Engine, elem: EditableElement, uri: Uri): Boolean {
        Log.d(TAG, "loadTexture: ${elem.meshName}")
        return try {
            elem.activeSourceBitmap?.let { if (!it.isRecycled) it.recycle() }
            val source = decodeBitmapSafe(uri) ?: return false
            elem.activeSourceBitmap = source

            if (elem.activeTexture == null) {
                elem.displayBitmap = Bitmap.createBitmap(TEX_SIZE, TEX_SIZE, Bitmap.Config.ARGB_8888)
                val mipLevels = (kotlin.math.log2(TEX_SIZE.toFloat())).toInt() + 1
                val usage = Texture.Usage.SAMPLEABLE or Texture.Usage.COLOR_ATTACHMENT or
                        Texture.Usage.UPLOADABLE or Texture.Usage.GEN_MIPMAPPABLE
                val tex = Texture.Builder()
                    .width(TEX_SIZE).height(TEX_SIZE).levels(mipLevels)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .format(Texture.InternalFormat.SRGB8_A8)
                    .usage(usage).build(engine)
                texturePool.add(tex)
                elem.activeTexture = tex
            }

            elem.hasCustomTexture = true
            renderSeparateMeshBitmap(elem)

            val rtName = when (elem.type) {
                ElementType.EYE_LEFT -> RT_EYE_L
                ElementType.EYE_RIGHT -> RT_EYE_R
                ElementType.TEETH -> RT_TEETH
                else -> null
            }
            if (rtName != null) saveRuntimeBitmap(elem.displayBitmap, rtName)

            postGpuOp { uploadSeparateMeshTexture(engine, elem) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadTexture FAILED", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TRANSFORM
    // ═══════════════════════════════════════════════════════════════

    fun applyTransform(
        engine: Engine, elem: EditableElement,
        scaleX: Float = elem.uvScaleX, scaleY: Float = elem.uvScaleY,
        offsetX: Float = elem.uvOffsetX, offsetY: Float = elem.uvOffsetY,
        rotDeg: Float = elem.uvRotationDeg,
    ) {
        elem.uvScaleX = scaleX; elem.uvScaleY = scaleY
        elem.uvOffsetX = offsetX; elem.uvOffsetY = offsetY
        elem.uvRotationDeg = rotDeg

        if (elem.type == ElementType.HEAD_ZONE && elem.headZone != null) {
            val zd = zoneDataMap[elem.headZone] ?: return
            zd.uvScaleX = scaleX; zd.uvScaleY = scaleY
            zd.uvOffsetX = offsetX; zd.uvOffsetY = offsetY
            zd.uvRotationDeg = rotDeg
            if (zd.hasTexture) {
                compositeAndUploadHead(engine)
            }
        } else if (elem.hasCustomTexture) {
            renderSeparateMeshBitmap(elem)
            postGpuOp { uploadSeparateMeshTexture(engine, elem) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HEAD COMPOSITE
    //  ИСПРАВЛЕНО: каждый раз создаём НОВУЮ текстуру.
    //  Старую текстуру откладываем на уничтожение через 2 flush-цикла (~32ms),
    //  чтобы GPU гарантированно закончил из неё читать.
    // ═══════════════════════════════════════════════════════════════

    private fun ensureHeadCompositeTexture(engine: Engine) {
        if (headCompositeBitmap != null) return
        headCompositeBitmap = Bitmap.createBitmap(TEX_SIZE, TEX_SIZE, Bitmap.Config.ARGB_8888)
        Log.d(TAG, "Head composite bitmap created")
    }

    private fun compositeAndUploadHead(engine: Engine) {
        val composite = headCompositeBitmap ?: return
        if (composite.isRecycled) return

        val compositeCanvas = Canvas(composite)
        compositeCanvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Фон: тёплый цвет кожи (правильный — не кремовый)
        val bgColor = if (headBgColor != android.graphics.Color.TRANSPARENT) headBgColor
                      else SKIN_COLOR_SRGB
        compositeCanvas.drawColor(bgColor)

        // ═══ ДОБАВИТЬ: розовый цвет для внутренней части рта ═══
        val mouthZd = zoneDataMap[HeadZone.MOUTH_INNER]
        if (mouthZd != null && !mouthZd.hasTexture) {
            val mouthColor = android.graphics.Color.rgb(194, 84, 71)
            val mouthBuf = Bitmap.createBitmap(TEX_SIZE, TEX_SIZE, Bitmap.Config.ARGB_8888)
            val mouthCanvas = Canvas(mouthBuf)
            mouthCanvas.drawColor(mouthColor)
            val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            mouthCanvas.drawBitmap(mouthZd.maskBitmap, 0f, 0f, maskPaint)
            compositeCanvas.drawBitmap(mouthBuf, 0f, 0f, Paint())
            mouthBuf.recycle()
        }
        // ═══ КОНЕЦ ДОБАВЛЕНИЯ ═══

        val zoneBuf = Bitmap.createBitmap(TEX_SIZE, TEX_SIZE, Bitmap.Config.ARGB_8888)
        val zoneCanvas = Canvas(zoneBuf)
        val srcPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
        val maskApply = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

        for (zone in HeadZone.entries) {
            val zd = zoneDataMap[zone] ?: continue
            val src = zd.sourceBitmap ?: continue
            if (!zd.hasTexture || src.isRecycled) continue

            zoneCanvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            val matrix = Matrix()
            val srcRect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            val dstRect = RectF(0f, 0f, TEX_SIZE.toFloat(), TEX_SIZE.toFloat())
            matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.CENTER)

            val cx = TEX_SIZE / 2f; val cy = TEX_SIZE / 2f
            matrix.postTranslate(zd.uvOffsetX * TEX_SIZE, zd.uvOffsetY * TEX_SIZE)
            matrix.postScale(zd.uvScaleX, zd.uvScaleY, cx, cy)
            matrix.postRotate(zd.uvRotationDeg, cx, cy)

            zoneCanvas.drawBitmap(src, matrix, srcPaint)
            zoneCanvas.drawBitmap(zd.maskBitmap, 0f, 0f, maskApply)
            compositeCanvas.drawBitmap(zoneBuf, 0f, 0f, srcPaint)
        }
        zoneBuf.recycle()

        saveRuntimeBitmap(composite, RT_HEAD)

        // Захватываем текущий flushCount для замыкания
        val capturedFlushCount = flushCount

        postGpuOp {
            val mi = headMaterialInstance ?: return@postGpuOp
            if (composite.isRecycled) return@postGpuOp

            // Снимок битмапа ДО создания новой текстуры
            val uploadBmp = composite.copy(Bitmap.Config.ARGB_8888, false)

            // Откладываем уничтожение СТАРОЙ текстуры на 2 flush-цикла
            // (GPU гарантированно завершит чтение за ~32ms при 60fps)
            headCompositeTexture?.let { old ->
                texturePool.remove(old)
                texturesToDestroy.add(Pair(old, capturedFlushCount + 2))
                Log.d(TAG, "Old head texture queued for deferred destroy at flush ${capturedFlushCount + 2}")
            }

            // ВСЕГДА создаём новую текстуру — никакого reuse
            val mipLevels = (kotlin.math.log2(TEX_SIZE.toFloat())).toInt() + 1
            val usage = Texture.Usage.SAMPLEABLE or Texture.Usage.COLOR_ATTACHMENT or
                    Texture.Usage.UPLOADABLE or Texture.Usage.GEN_MIPMAPPABLE
            val newTex = Texture.Builder()
                .width(TEX_SIZE).height(TEX_SIZE).levels(mipLevels)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8)
                .usage(usage)
                .build(engine)
            headCompositeTexture = newTex
            texturePool.add(newTex)

            try {
                val sampler = TextureSampler().apply {
                    setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                    setMagFilter(TextureSampler.MagFilter.LINEAR)
                    setWrapModeS(TextureSampler.WrapMode.CLAMP_TO_EDGE)
                    setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE)
                }
                TextureHelper.setBitmap(engine, newTex, 0, uploadBmp)
                newTex.generateMipmaps(engine)
                mi.setParameter("baseColorMap", newTex, sampler)
                safeSet4f(mi, "baseColorFactor", 1f, 1f, 1f, 1f)
                safeSet1f(mi, "metallicFactor", 0f)
                safeSet1f(mi, "roughnessFactor", 0.6f)
                Log.d(TAG, "Head composite UPLOADED OK (fresh texture)")
            } catch (e: Exception) {
                Log.e(TAG, "Head composite upload FAILED", e)
            } finally {
                uploadBmp.recycle()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEPARATE MESH TEXTURE
    // ═══════════════════════════════════════════════════════════════

    private fun renderSeparateMeshBitmap(elem: EditableElement) {
        val src = elem.activeSourceBitmap ?: return
        val display = elem.displayBitmap ?: return
        if (src.isRecycled || display.isRecycled) return

        val canvas = Canvas(display)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        workMatrix.reset()
        val srcR = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        val dstR = RectF(0f, 0f, display.width.toFloat(), display.height.toFloat())
        workMatrix.setRectToRect(srcR, dstR, Matrix.ScaleToFit.CENTER)

        val cx = display.width / 2f; val cy = display.height / 2f
        workMatrix.postTranslate(elem.uvOffsetX * display.width, elem.uvOffsetY * display.height)
        workMatrix.postScale(elem.uvScaleX, elem.uvScaleY, cx, cy)
        workMatrix.postRotate(elem.uvRotationDeg, cx, cy)

        canvas.drawBitmap(src, workMatrix, workPaint)
    }

    private fun uploadSeparateMeshTexture(engine: Engine, elem: EditableElement) {
        val display = elem.displayBitmap ?: return
        val tex = elem.activeTexture ?: return
        if (display.isRecycled) return

        try {
            val isEye = elem.type == ElementType.EYE_LEFT || elem.type == ElementType.EYE_RIGHT
            val sampler = TextureSampler().apply {
                setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                setMagFilter(TextureSampler.MagFilter.LINEAR)
                val wrap = if (isEye) TextureSampler.WrapMode.REPEAT
                else TextureSampler.WrapMode.CLAMP_TO_EDGE
                setWrapModeS(wrap); setWrapModeT(wrap)
            }
            TextureHelper.setBitmap(engine, tex, 0, display)
            tex.generateMipmaps(engine)
            elem.materialInstance.setParameter("baseColorMap", tex, sampler)
            safeSet4f(elem.materialInstance, "baseColorFactor", 1f, 1f, 1f, 1f)
        } catch (e: Exception) {
            Log.e(TAG, "uploadTexture FAILED: ${elem.meshName}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  COLOR / PBR
    // ═══════════════════════════════════════════════════════════════

    fun setColor(elem: EditableElement, r: Float, g: Float, b: Float) {
        elem.currentR = r; elem.currentG = g; elem.currentB = b
        postGpuOp { safeSet4f(elem.materialInstance, "baseColorFactor", r, g, b, 1f) }
    }

    fun setMetallic(elem: EditableElement, value: Float) {
        elem.currentMetallic = value
        postGpuOp { safeSet1f(elem.materialInstance, "metallicFactor", value) }
    }

    fun setRoughness(elem: EditableElement, value: Float) {
        elem.currentRoughness = value
        postGpuOp { safeSet1f(elem.materialInstance, "roughnessFactor", value) }
    }

    fun setHeadBackgroundColor(engine: Engine, color: Int) {
        headBgColor = color
        if (headMaterialInstance == null) return
        ensureHeadCompositeTexture(engine)
        compositeAndUploadHead(engine)
    }

    fun getHeadBgColor(): Int = headBgColor

    fun getHeadCompositeBitmap(): android.graphics.Bitmap? = headCompositeBitmap

    fun getEyesBitmap(): Bitmap? {
        // Берём левый глаз (правый — зеркальная копия той же текстуры)
        return elements.firstOrNull { it.type == ElementType.EYE_LEFT }?.displayBitmap
            ?: elements.firstOrNull { it.type == ElementType.EYE_RIGHT }?.displayBitmap
    }

    fun getMouthBitmap(): Bitmap? {
        val zone = elements.firstOrNull {
            it.type == ElementType.HEAD_ZONE && it.headZone == HeadZone.MOUTH_INNER
        } ?: return null
        val zd = zoneDataMap[HeadZone.MOUTH_INNER] ?: return null
        return zd.sourceBitmap
    }

    fun getTeethBitmap(): Bitmap? {
        return elements.firstOrNull { it.type == ElementType.TEETH }?.displayBitmap
    }

    private fun saveRuntimeBitmap(bitmap: Bitmap?, fileName: String) {
        if (bitmap == null || bitmap.isRecycled) return
        try {
            val file = File(context.cacheDir, fileName)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "Runtime texture saved: $fileName (${bitmap.width}x${bitmap.height})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save runtime texture: $fileName", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  LABELS
    // ═══════════════════════════════════════════════════════════════

    fun getLabel(elem: EditableElement): String = when (elem.type) {
        ElementType.HEAD_ZONE -> elem.headZone?.label ?: elem.meshName
        ElementType.EYE_LEFT -> "Глаз левый"
        ElementType.EYE_RIGHT -> "Глаз правый"
        ElementType.TEETH -> "Зубы"
        else -> elem.meshName
    }

    fun getElements() = elements.toList()

    // ═══════════════════════════════════════════════════════════════
    //  EXPORT
    // ═══════════════════════════════════════════════════════════════

    fun saveToStream(sourceGlbPath: String, outputStream: OutputStream): Boolean {
        return try {
            val bytes = buildGlbBytes(sourceGlbPath) ?: return false
            outputStream.write(bytes); outputStream.flush(); true
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    fun saveToFile(sourceGlbPath: String, outputFile: File): Boolean {
        return try {
            val bytes = buildGlbBytes(sourceGlbPath) ?: return false
            outputFile.writeBytes(bytes); true
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    private fun buildGlbBytes(sourceGlbPath: String): ByteArray? {
        return try {
            val data = File(sourceGlbPath).readBytes()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(12)
            val jsonLen = buf.int; buf.int
            val jsonBytes = ByteArray(jsonLen); buf.get(jsonBytes)
            val gltf = JSONObject(String(jsonBytes))
            val origBin = if (buf.remaining() >= 8) {
                val bl = buf.int; buf.int; ByteArray(bl).also { buf.get(it) }
            } else ByteArray(0)

            val binParts = mutableListOf(origBin)

            val headBmp = headCompositeBitmap
            val hasHeadTexture = headBmp != null && !headBmp.isRecycled &&
                    (zoneDataMap.values.any { it.hasTexture } || headBgColor != android.graphics.Color.TRANSPARENT)

            if (hasHeadTexture) {
                val baos = ByteArrayOutputStream()
                headBmp!!.compress(Bitmap.CompressFormat.PNG, 100, baos)
                addTextureToGltf(gltf, "baked_head_composite", baos.toByteArray(),
                    "head_lod0_ORIGINAL", false, binParts)
            }

            if (!hasHeadTexture && headBgColor != android.graphics.Color.TRANSPARENT) {
                val exportBmp = Bitmap.createBitmap(TEX_SIZE, TEX_SIZE, Bitmap.Config.ARGB_8888)
                Canvas(exportBmp).drawColor(headBgColor)
                val baos = ByteArrayOutputStream()
                exportBmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                exportBmp.recycle()
                addTextureToGltf(gltf, "baked_head_bg", baos.toByteArray(),
                    "head_lod0_ORIGINAL", false, binParts)
            }

            for (elem in elements) {
                if (elem.type == ElementType.HEAD_ZONE) continue
                val bmp = elem.displayBitmap ?: continue
                if (!elem.hasCustomTexture || bmp.isRecycled) continue
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                addTextureToGltf(gltf, "baked_${elem.meshName}", baos.toByteArray(),
                    elem.meshName, elem.type == ElementType.EYE_LEFT || elem.type == ElementType.EYE_RIGHT,
                    binParts)
            }

            for (elem in elements) {
                if (elem.type == ElementType.HEAD_ZONE) continue
                if (elem.hasCustomTexture) continue
                val matIdx = findMaterialIndex(gltf, elem.meshName)
                if (matIdx >= 0) {
                    val mat = gltf.getJSONArray("materials").getJSONObject(matIdx)
                    val pbr = mat.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
                    pbr.put("baseColorFactor", JSONArray(listOf(
                        elem.currentR.toDouble(), elem.currentG.toDouble(),
                        elem.currentB.toDouble(), 1.0)))
                    pbr.put("roughnessFactor", elem.currentRoughness.toDouble())
                    pbr.put("metallicFactor", elem.currentMetallic.toDouble())
                    mat.put("pbrMetallicRoughness", pbr)
                }
            }

            val totalBinSize = binParts.sumOf { it.size }
            val buffersArr = gltf.optJSONArray("buffers")
                ?: JSONArray().also { gltf.put("buffers", it) }
            if (buffersArr.length() > 0) buffersArr.getJSONObject(0).put("byteLength", totalBinSize)
            else buffersArr.put(JSONObject().apply { put("byteLength", totalBinSize) })

            val finalBin = ByteArray(totalBinSize)
            var offset = 0
            for (part in binParts) {
                System.arraycopy(part, 0, finalBin, offset, part.size)
                offset += part.size
            }

            assembleGlb(gltf, finalBin)
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    private fun addTextureToGltf(
        gltf: JSONObject, name: String, jpegBytes: ByteArray,
        meshName: String, isEye: Boolean,
        binChunkRef: MutableList<ByteArray>,
    ) {
        val bufferViews = gltf.optJSONArray("bufferViews")
            ?: JSONArray().also { gltf.put("bufferViews", it) }

        val currentOffset = binChunkRef.sumOf { it.size }

        val padding = (4 - jpegBytes.size % 4) % 4
        val paddedBytes = jpegBytes + ByteArray(padding)
        binChunkRef.add(paddedBytes)

        val bvIdx = bufferViews.length()
        bufferViews.put(JSONObject().apply {
            put("buffer", 0)
            put("byteOffset", currentOffset)
            put("byteLength", jpegBytes.size)
        })

        val imgArr = gltf.optJSONArray("images")
            ?: JSONArray().also { gltf.put("images", it) }
        val imgIdx = imgArr.length()
        imgArr.put(JSONObject().apply {
            put("name", name)
            put("mimeType", "image/png")
            put("bufferView", bvIdx)
        })

        val sampArr = gltf.optJSONArray("samplers") ?: JSONArray().also { gltf.put("samplers", it) }
        val sampIdx = sampArr.length()
        val wrapType = if (isEye) 10497 else 33071
        sampArr.put(JSONObject().apply {
            put("magFilter", 9729); put("minFilter", 9987)
            put("wrapS", wrapType); put("wrapT", wrapType)
        })

        val texArr = gltf.optJSONArray("textures") ?: JSONArray().also { gltf.put("textures", it) }
        val texIdx = texArr.length()
        texArr.put(JSONObject().apply { put("source", imgIdx); put("sampler", sampIdx) })

        val matIdx = findMaterialIndex(gltf, meshName)
        if (matIdx >= 0) {
            val mat = gltf.getJSONArray("materials").getJSONObject(matIdx)
            val pbr = mat.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
            pbr.put("baseColorTexture", JSONObject().apply { put("index", texIdx); put("texCoord", 0) })
            pbr.put("baseColorFactor", JSONArray(listOf(1.0, 1.0, 1.0, 1.0)))
            pbr.put("metallicFactor", 0.0)
            pbr.put("roughnessFactor", 0.6)
            mat.put("pbrMetallicRoughness", pbr)
        }
    }

    private fun assembleGlb(gltf: JSONObject, binChunk: ByteArray): ByteArray {
        val newJson = gltf.toString()
        val jsonPad = (4 - newJson.length % 4) % 4
        val jb = (newJson + " ".repeat(jsonPad)).toByteArray(Charsets.UTF_8)
        val binPad = (4 - binChunk.size % 4) % 4
        val bp = if (binPad > 0) binChunk + ByteArray(binPad) else binChunk
        val total = 12 + 8 + jb.size + 8 + bp.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
        out.putInt(jb.size); out.putInt(0x4E4F534A); out.put(jb)
        out.putInt(bp.size); out.putInt(0x004E4942); out.put(bp)
        return out.array()
    }

    private fun findMaterialIndex(gltf: JSONObject, meshName: String): Int {
        val meshes = gltf.optJSONArray("meshes") ?: return -1
        val searchName = if (meshName.startsWith("head_zone_")) "head_lod0_ORIGINAL" else meshName
        for (i in 0 until meshes.length()) {
            val m = meshes.getJSONObject(i)
            if (m.optString("name") == searchName) {
                val prims = m.optJSONArray("primitives") ?: continue
                if (prims.length() > 0) return prims.getJSONObject(0).optInt("material", -1)
            }
        }
        return -1
    }

    fun ensureSourceInCache(assetPath: String): File {
        val f = File(context.cacheDir, "source_for_edit.glb")
        if (f.exists()) {
            f.delete()
        }
        if (!f.exists()) {
            context.assets.open(assetPath).use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }
        }
        return f
    }

    // ═══════════════════════════════════════════════════════════════
    //  DESTROY
    // ═══════════════════════════════════════════════════════════════

    fun destroy(engine: Engine) {
        Log.d(TAG, "destroy")
        synchronized(gpuOpsLock) { pendingGpuOps.clear() }

        // Убираем engine.destroyTexture(), так как SceneView сам уничтожит Engine 
        // и все его ресурсы при закрытии экрана.
        texturesToDestroy.clear()
        texturePool.clear()

        elements.forEach { elem ->
            elem.activeSourceBitmap?.let { if (!it.isRecycled) it.recycle() }
            elem.displayBitmap?.let { if (!it.isRecycled) it.recycle() }
            elem.activeSourceBitmap = null; elem.displayBitmap = null; elem.activeTexture = null
        }
        elements.clear()
        zoneDataMap.values.forEach { zd ->
            zd.recycle()
            if (!zd.maskBitmap.isRecycled) zd.maskBitmap.recycle()
        }
        zoneDataMap.clear()
        headCompositeBitmap?.let { if (!it.isRecycled) it.recycle() }
        headCompositeBitmap = null
        headCompositeTexture = null
        headMaterialInstance = null
    }
}
