package com.codeextractor.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

data class GlbMaterialInfo(
    val index: Int,
    val name: String,
    val baseColor: FloatArray,
    val metallic: Float,
    val roughness: Float,
    val hasTexture: Boolean
)

class GlbEditor(private val context: Context) {

    private lateinit var gltf: JSONObject
    private var binChunk = ByteArray(0)

    fun loadFromAssets(path: String): Boolean = try {
        context.assets.open(path).use { parseGlb(it.readBytes()) }
        true
    } catch (e: Exception) { e.printStackTrace(); false }

    fun loadFromFile(file: File): Boolean = try {
        parseGlb(file.readBytes()); true
    } catch (e: Exception) { e.printStackTrace(); false }

    private fun parseGlb(data: ByteArray) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12) // skip header
        val jsonLen = buf.int; buf.int // skip type
        val jsonBytes = ByteArray(jsonLen); buf.get(jsonBytes)
        gltf = JSONObject(String(jsonBytes))
        if (buf.remaining() >= 8) {
            val binLen = buf.int; buf.int
            binChunk = ByteArray(binLen); buf.get(binChunk)
        }
    }

    fun getMaterials(): List<GlbMaterialInfo> {
        val mats = gltf.getJSONArray("materials")
        return (0 until mats.length()).map { i ->
            val m = mats.getJSONObject(i)
            val pbr = m.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
            val bcf = pbr.optJSONArray("baseColorFactor")
            val color = if (bcf != null) FloatArray(4) { bcf.getDouble(it).toFloat() }
                        else floatArrayOf(1f, 1f, 1f, 1f)
            GlbMaterialInfo(
                index = i,
                name = m.optString("name", "Material_$i"),
                baseColor = color,
                metallic = pbr.optDouble("metallicFactor", 1.0).toFloat(),
                roughness = pbr.optDouble("roughnessFactor", 1.0).toFloat(),
                hasTexture = pbr.optJSONObject("baseColorTexture") != null
            )
        }
    }

    fun setColor(matIndex: Int, r: Float, g: Float, b: Float, a: Float = 1f) {
        val pbr = ensurePbr(matIndex)
        pbr.put("baseColorFactor", JSONArray(listOf(r.toDouble(), g.toDouble(), b.toDouble(), a.toDouble())))
    }

    fun setMetallicRoughness(matIndex: Int, metallic: Float, roughness: Float) {
        val pbr = ensurePbr(matIndex)
        pbr.put("metallicFactor", metallic.toDouble())
        pbr.put("roughnessFactor", roughness.toDouble())
    }

    fun setTexture(matIndex: Int, bitmap: Bitmap, quality: Int = 90) {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)

        val images = gltf.optJSONArray("images") ?: JSONArray().also { gltf.put("images", it) }
        images.put(JSONObject().put("name", "tex_mat$matIndex").put("mimeType", "image/jpeg")
            .put("uri", "data:image/jpeg;base64,$b64"))

        val samplers = gltf.optJSONArray("samplers") ?: JSONArray().also {
            it.put(JSONObject().put("magFilter", 9729).put("minFilter", 9987)
                .put("wrapS", 33071).put("wrapT", 33071))
            gltf.put("samplers", it)
        }

        val textures = gltf.optJSONArray("textures") ?: JSONArray().also { gltf.put("textures", it) }
        textures.put(JSONObject().put("source", images.length() - 1).put("sampler", 0))

        val pbr = ensurePbr(matIndex)
        pbr.put("baseColorTexture", JSONObject().put("index", textures.length() - 1).put("texCoord", 0))
        pbr.put("baseColorFactor", JSONArray(listOf(1.0, 1.0, 1.0, 1.0)))
    }

    fun removeTexture(matIndex: Int) {
        ensurePbr(matIndex).remove("baseColorTexture")
    }

    fun save(out: File): Boolean = try {
        val json = gltf.toString()
        val padded = json + " ".repeat((4 - json.length % 4) % 4)
        val jb = padded.toByteArray(Charsets.UTF_8)
        val bp = if (binChunk.size % 4 != 0) binChunk + ByteArray((4 - binChunk.size % 4) % 4) else binChunk
        val total = 12 + 8 + jb.size + 8 + bp.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46546C67); buf.putInt(2); buf.putInt(total)
        buf.putInt(jb.size); buf.putInt(0x4E4F534A); buf.put(jb)
        buf.putInt(bp.size); buf.putInt(0x004E4942); buf.put(bp)
        out.writeBytes(buf.array()); true
    } catch (e: Exception) { e.printStackTrace(); false }

    private fun ensurePbr(matIndex: Int): JSONObject {
        val mat = gltf.getJSONArray("materials").getJSONObject(matIndex)
        return mat.optJSONObject("pbrMetallicRoughness")
            ?: JSONObject().also { mat.put("pbrMetallicRoughness", it) }
    }
}