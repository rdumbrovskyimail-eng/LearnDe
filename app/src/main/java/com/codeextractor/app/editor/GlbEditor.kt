package com.codeextractor.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import io.github.sceneview.model.ModelInstance
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Информация о редактируемом элементе модели.
 * Каждый entity в GLB — это отдельный меш со своим материалом.
 */
data class EditableElement(
    val entity: Int,
    val renderableInstance: Int,
    val primitiveIndex: Int,
    val meshName: String,
    val materialInstance: MaterialInstance,
)

/**
 * Редактор текстур/материалов GLB-модели через Filament API.
 * Все изменения — мгновенные, без перезагрузки модели.
 */
class GlbTextureEditor(private val context: Context) {

    private val elements = mutableListOf<EditableElement>()
    private val appliedBitmaps = mutableMapOf<Int, Bitmap>() // elementIndex → bitmap
    private val createdTextures = mutableListOf<Texture>()

    // Имена мешей из source_named.glb
    private val MESH_LABELS = mapOf(
        "teeth_ORIGINAL"    to "Зубы",
        "head_lod0_ORIGINAL" to "Голова / Кожа",
        "eyeLeft_ORIGINAL"  to "Левый глаз",
        "eyeRight_ORIGINAL" to "Правый глаз",
    )

    /**
     * Сканирует ModelInstance и собирает все редактируемые элементы.
     * Вызывать после загрузки модели.
     */
    fun scanModel(engine: Engine, modelInstance: ModelInstance): List<EditableElement> {
        elements.clear()
        val rm = engine.renderableManager

        // Получаем имена через FilamentAsset если доступно
        // В SceneView 3.x entities — массив всех entity в модели
        val entities = modelInstance.entities

        for (entity in entities) {
            if (!rm.hasComponent(entity)) continue
            val ri = rm.getInstance(entity)
            val primCount = rm.getPrimitiveCount(ri)

            for (prim in 0 until primCount) {
                val mi = rm.getMaterialInstanceAt(ri, prim)

                // Определяем имя меша по morph target count
                val morphCount = rm.getMorphTargetCount(ri)
                val meshName = when (morphCount) {
                    51 -> "head_lod0_ORIGINAL"
                    5  -> "teeth_ORIGINAL"
                    4  -> if (elements.count { it.meshName.startsWith("eye") } == 0)
                              "eyeLeft_ORIGINAL" else "eyeRight_ORIGINAL"
                    else -> "unknown_$entity"
                }

                elements.add(
                    EditableElement(
                        entity = entity,
                        renderableInstance = ri,
                        primitiveIndex = prim,
                        meshName = meshName,
                        materialInstance = mi,
                    )
                )
            }
        }

        return elements.toList()
    }

    fun getElements() = elements.toList()

    fun getLabel(element: EditableElement): String =
        MESH_LABELS[element.meshName] ?: element.meshName

    /**
     * Установить цвет материала (baseColorFactor) — мгновенно.
     */
    fun setColor(element: EditableElement, r: Float, g: Float, b: Float, a: Float = 1f) {
        try {
            element.materialInstance.setParameter(
                "baseColorFactor", r, g, b, a
            )
        } catch (e: Exception) {
            // Fallback: некоторые материалы могут не иметь этого параметра
            e.printStackTrace()
        }
    }

    /**
     * Установить metallic/roughness — мгновенно.
     */
    fun setMetallic(element: EditableElement, value: Float) {
        try { element.materialInstance.setParameter("metallicFactor", value) }
        catch (_: Exception) {}
    }

    fun setRoughness(element: EditableElement, value: Float) {
        try { element.materialInstance.setParameter("roughnessFactor", value) }
        catch (_: Exception) {}
    }

    /**
     * Загрузить Bitmap из Uri и применить как текстуру на элемент.
     */
    fun applyTextureFromUri(
        engine: Engine,
        element: EditableElement,
        uri: Uri,
        elementIndex: Int
    ): Boolean {
        val bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            e.printStackTrace(); null
        } ?: return false

        return applyTextureBitmap(engine, element, bitmap, elementIndex)
    }

    /**
     * Применить Bitmap как текстуру — мгновенно через Filament API.
     */
    fun applyTextureBitmap(
        engine: Engine,
        element: EditableElement,
        bitmap: Bitmap,
        elementIndex: Int
    ): Boolean {
        return try {
            // Масштабируем если слишком большой (max 2048 для мобильных GPU)
            val maxSize = 2048
            val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
                val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else bitmap

            // Создаём Filament Texture
            val texture = Texture.Builder()
                .width(scaledBitmap.width)
                .height(scaledBitmap.height)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8)
                .levels(0xff) // auto mip levels
                .build(engine)

            // Загружаем пиксели из Bitmap
            TextureHelper.setBitmap(engine, texture, 0, scaledBitmap)
            texture.generateMipmaps(engine)

            // Настраиваем sampler
            val sampler = TextureSampler().apply {
                setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                setMagFilter(TextureSampler.MagFilter.LINEAR)
                setWrapModeS(TextureSampler.WrapMode.CLAMP_TO_EDGE)
                setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE)
            }

            // Применяем на материал
            element.materialInstance.setParameter("baseColorMap", texture, sampler)

            // Сбрасываем baseColorFactor на белый чтобы текстура была видна
            element.materialInstance.setParameter("baseColorFactor", 1f, 1f, 1f, 1f)

            // Сохраняем для последующего сохранения в GLB
            appliedBitmaps[elementIndex] = scaledBitmap
            createdTextures.add(texture)

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Сохранить все изменения в GLB файл.
     * Модифицирует JSON-чанк GLB: добавляет текстуры как data URI.
     */
    fun saveToGlb(sourceGlbPath: String, outputFile: File): Boolean {
        return try {
            val data = File(sourceGlbPath).readBytes()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Parse header
            buf.position(12) // skip magic+version+length
            val jsonLen = buf.int; buf.int // skip type
            val jsonBytes = ByteArray(jsonLen); buf.get(jsonBytes)
            val gltf = JSONObject(String(jsonBytes))

            // Binary chunk
            val binChunk = if (buf.remaining() >= 8) {
                val binLen = buf.int; buf.int
                ByteArray(binLen).also { buf.get(it) }
            } else ByteArray(0)

            // Для каждого сохранённого bitmap — добавляем в GLB
            for ((elementIdx, bitmap) in appliedBitmaps) {
                if (elementIdx >= elements.size) continue
                val elem = elements[elementIdx]

                // Encode bitmap to JPEG base64
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                val b64 = android.util.Base64.encodeToString(
                    baos.toByteArray(), android.util.Base64.NO_WRAP
                )
                val dataUri = "data:image/jpeg;base64,$b64"

                // Add image
                val images = gltf.optJSONArray("images")
                    ?: JSONArray().also { gltf.put("images", it) }
                images.put(JSONObject().apply {
                    put("name", "texture_${elem.meshName}")
                    put("mimeType", "image/jpeg")
                    put("uri", dataUri)
                })

                // Ensure sampler
                if (!gltf.has("samplers") || gltf.getJSONArray("samplers").length() == 0) {
                    gltf.put("samplers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("magFilter", 9729)
                            put("minFilter", 9987)
                            put("wrapS", 33071)
                            put("wrapT", 33071)
                        })
                    })
                }

                // Add texture reference
                val textures = gltf.optJSONArray("textures")
                    ?: JSONArray().also { gltf.put("textures", it) }
                textures.put(JSONObject().apply {
                    put("source", images.length() - 1)
                    put("sampler", 0)
                })

                // Find material index for this element
                val matIdx = findMaterialIndex(gltf, elem.meshName)
                if (matIdx >= 0) {
                    val mat = gltf.getJSONArray("materials").getJSONObject(matIdx)
                    val pbr = mat.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
                    pbr.put("baseColorTexture", JSONObject().apply {
                        put("index", textures.length() - 1)
                        put("texCoord", 0)
                    })
                    pbr.put("baseColorFactor", JSONArray(listOf(1.0, 1.0, 1.0, 1.0)))
                    mat.put("pbrMetallicRoughness", pbr)
                }
            }

            // Write GLB
            val newJson = gltf.toString()
            val padded = newJson + " ".repeat((4 - newJson.length % 4) % 4)
            val jb = padded.toByteArray(Charsets.UTF_8)
            val bp = if (binChunk.size % 4 != 0)
                binChunk + ByteArray((4 - binChunk.size % 4) % 4) else binChunk
            val total = 12 + 8 + jb.size + 8 + bp.size

            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
            out.putInt(jb.size); out.putInt(0x4E4F534A); out.put(jb)
            out.putInt(bp.size); out.putInt(0x004E4942); out.put(bp)
            outputFile.writeBytes(out.array())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun findMaterialIndex(gltf: JSONObject, meshName: String): Int {
        val meshes = gltf.optJSONArray("meshes") ?: return -1
        for (i in 0 until meshes.length()) {
            val m = meshes.getJSONObject(i)
            if (m.optString("name") == meshName) {
                return m.getJSONArray("primitives")
                    .getJSONObject(0).optInt("material", -1)
            }
        }
        return -1
    }

    fun destroy(engine: Engine) {
        createdTextures.forEach {
            try { engine.destroyTexture(it) } catch (_: Exception) {}
        }
        createdTextures.clear()
        appliedBitmaps.clear()
    }
}