/**
 * DIAGNOSTIKA KRASHA: "Texture is not SAMPLEABLE"
 * Konsolidaciya: Claude + Grok + Gemini
 *
 * KAK ISPOLZOVAT:
 * 1. Vstav fail v com.codeextractor.app.editor
 * 2. Vyzovi TextureCrashDiagnostic.runAll(engine, mi)
 *    iz LaunchedEffect POSLE scanModel (sm. niz faila)
 * 3. Logcat filtr: "TEX_DIAG"
 * 4. test4 i test9 MOGUT krashit! Zapuskaj po odnomu.
 *
 * POLNYJ SPISOK PRICHIN (deduplicirovano iz 3 istochnikov):
 *
 * -- A: Usage flags (veroyatnost 80%+) --
 *  A1. Kotlin or na enum ordinals vmesto bitovyh masok
 *  A2. GEN_MIPMAPPABLE ne suschestvuet / =0
 *  A3. COLOR_ATTACHMENT konflikt s SAMPLEABLE na Vulkan Samsung
 *  A4. UPLOADABLE menyaet allocation path
 *  A5. Kombinaciya 4 flagov zapreschena drajverom
 *  A6. SRGB8_A8 + COLOR_ATTACHMENT -> SAMPLEABLE sbroshen
 *  A7. levels(11) + GEN_MIPMAPPABLE -> render-target-only
 *  A8. Drajver tikho fejlit sozdanie tekstury
 *
 * -- B: Poryadok vyzovov (veroyatnost 50%) --
 *  B1. setParameter DO setBitmap -> tekstura pustaya
 *  B2. Sampler LINEAR_MIPMAP_LINEAR no pikseli tolko na level 0
 *  B3. generateMipmaps srazu posle setBitmap (asinhronen)
 *  B4. generateMipmaps bez memory barrier
 *  B5. setupHighEndPBR parallelno modificiruet MI
 *
 * -- C: Patchennaya model / gltfio --
 *  C1. Dummy 4x4 PNG bez mipmap -> MI zalocken
 *  C2. Sampler v GLB = LINEAR, iz koda = LINEAR_MIPMAP_LINEAR
 *  C3. gltfio zalockil baseColorMap parametr
 *
 * -- D: Threading / lifecycle --
 *  D1. flushPendingGpuOps ne sinhronizirovan s render pass
 *  D2. Engine destroyed do flush
 *  D3. Bitmap GC'd do zagruzki v GPU
 *
 * -- E: Format / razmer --
 *  E1. SRGB8_A8 + apparatnye mipmaps ne podderzhivayutsya
 *  E2. 1024x1024 + 11 mip levels > device limit
 */

package com.codeextractor.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper

object TextureCrashDiagnostic {

    private const val TAG = "TEX_DIAG"

    fun runAll(engine: Engine, mi: MaterialInstance? = null) {
        Log.w(TAG, "==== DIAGNOSTIKA START (10 testov) ====")

        test0_InspectUsageFlags()
        test1_DefaultUsage(engine, mi)
        // test2_SampleableOnly(engine, mi)  // BUG: net UPLOADABLE
        test3_SampleableUploadable(engine, mi)
        // test4_CurrentCode(engine, mi)  // RASKOMMENTIRUJ OTDELNO
        test5_Rgba8Format(engine, mi)
        test6_SingleMipLevel(engine, mi)
        test7_SetBitmapFirst(engine, mi)
        test8_SimpleSamplerNoMipmap(engine, mi)
        // test9_ColorAttachmentCombo(engine, mi) // RASKOMMENTIRUJ OTDELNO

        Log.w(TAG, "==== DIAGNOSTIKA END ====")
    }

    fun runSingle(engine: Engine, mi: MaterialInstance?, testNum: Int) {
        when (testNum) {
            0 -> test0_InspectUsageFlags()
            1 -> test1_DefaultUsage(engine, mi)
            2 -> test2_SampleableOnly(engine, mi)
            3 -> test3_SampleableUploadable(engine, mi)
            4 -> test4_CurrentCode(engine, mi)
            5 -> test5_Rgba8Format(engine, mi)
            6 -> test6_SingleMipLevel(engine, mi)
            7 -> test7_SetBitmapFirst(engine, mi)
            8 -> test8_SimpleSamplerNoMipmap(engine, mi)
            9 -> test9_ColorAttachmentCombo(engine, mi)
        }
    }

    // TEST 0: Indikator na faze - chto realno vnutri enum?
    // Ne krashit. Proverjaet: A1, A2
    private fun test0_InspectUsageFlags() {
        Log.w(TAG, "--- TEST 0: Usage enum inspection ---")
        try {
            val vals = mapOf(
                "SAMPLEABLE" to Texture.Usage.SAMPLEABLE,
                "UPLOADABLE" to Texture.Usage.UPLOADABLE,
                "COLOR_ATTACHMENT" to Texture.Usage.COLOR_ATTACHMENT,
            )
            vals.forEach { (name, v) ->
                Log.w(TAG, "  $name = $v")
            }

            try {
                val gm = Texture.Usage.GEN_MIPMAPPABLE
                Log.w(TAG, "  GEN_MIPMAPPABLE = $gm")
            } catch (e: Throwable) {
                Log.e(TAG, "  !!! GEN_MIPMAPPABLE NE SUSCHESTVUET: ${e::class.simpleName}")
            }

            val combined = Texture.Usage.SAMPLEABLE or Texture.Usage.UPLOADABLE
            Log.w(TAG, "  SAMPLEABLE or UPLOADABLE = $combined (type=${combined::class.java.simpleName})")

            val full = Texture.Usage.SAMPLEABLE or
                    Texture.Usage.COLOR_ATTACHMENT or
                    Texture.Usage.UPLOADABLE
            Log.w(TAG, "  S|C|U = $full (type=${full::class.java.simpleName})")

            if (combined is Int) {
                Log.w(TAG, "  combined binary: ${Integer.toBinaryString(combined)}")
                Log.w(TAG, "  full binary:     ${Integer.toBinaryString(full as Int)}")
                Log.w(TAG, "  (full AND 0x01) = ${full and 0x01} <- dolzhen byt 1 dlya SAMPLEABLE")
            }

            val builderClass = Texture.Builder::class.java
            builderClass.methods
                .filter { it.name == "usage" }
                .forEach { m ->
                    val params = m.parameterTypes.joinToString { it.simpleName }
                    Log.w(TAG, "  Builder.usage() signature: ($params) -> ${m.returnType.simpleName}")
                }

            Log.w(TAG, "  OK TEST 0")
        } catch (e: Throwable) {
            Log.e(TAG, "  FAIL TEST 0: ${e.message}")
        }
    }

    // TEST 1: Bez .usage() voobsche
    // Proverjaet: A1-A8
    private fun test1_DefaultUsage(engine: Engine, mi: MaterialInstance?) {
        Log.w(TAG, "--- TEST 1: Default usage (bez .usage()) ---")
        var tex: Texture? = null
        try {
            tex = Texture.Builder()
                .width(64).height(64)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8)
                .build(engine)

            val bmp = createTestBitmap(64)
            TextureHelper.setBitmap(engine, tex, 0, bmp)
            bmp.recycle()

            if (mi != null) {
                mi.setParameter("baseColorMap", tex, TextureSampler())
                Log.w(TAG, "  PASSED 1 - default usage RABOTAET")
                Log.w(TAG, "  -> Problema v .usage() flagah (gruppa A)")
            } else {
                Log.w(TAG, "  WARN 1 - Tekstura OK, MI=null")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "  FAIL 1: ${e.message}")
            Log.e(TAG, "  -> Problema NE v usage (gruppy C-G)")
        } finally {
            tex?.let { safely { engine.destroyTexture(it) } }
        }
    }

    // TEST 2: Tolko SAMPLEABLE
    private fun test2_SampleableOnly(engine: Engine, mi: MaterialInstance?) {
        Log.w(TAG, "--- TEST 2: Tolko SAMPLEABLE ---")
        var tex: Texture? = null
        try {
            tex = Texture.Builder()
                .width(64).height(64)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8)
                .usage(Texture.Usage.SAMPLEABLE)
                .build(engine)

            val bmp = createTestBitmap(64)
            TextureHelper.setBitmap(engine, tex, 0, bmp)
            bmp.recycle()

            if (mi != null) {
                mi.setParameter("baseColorMap", tex, TextureSampler())
                Log.w(TAG, "  PASSED 2")
            } else {
                Log.w(TAG, "  WARN 2 - OK, MI=null")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "  FAIL 2: ${e.message}")
        } finally {
            tex?.let { safely { engine.destroyTexture(it) } }
        }
    }

    // TEST 3: SAMPLEABLE | UPLOADABLE
    private fun test3_SampleableUploadable(engine: Engine, mi: MaterialInstance?) {
        Log.w(TAG, "--- TEST 3: SAMPLEABLE | UPLOADABLE ---")
        var tex: Texture? = null
        try {
            val usage = Texture.Usage.SAMPLEABLE or Texture.Usage.UPLOADABLE
            Log.w(TAG, "  usage = $usage")

            tex = Texture.Builder()
                .width(64).height(64)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8)
                .usage(usage)
                .build(engine)

            val bmp = createTestBitmap(64)
            TextureHelper.setBitmap(engine, tex, 0, bmp)
            bmp.recycle()

            if (mi != null) {
                mi.setParameter("baseColorMap", tex, TextureSampler())
                Log.w(TAG, "  PASSED 3")
            } else {
                Log.w(TAG, "  WARN 3 - OK, MI=null")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "  FAIL 3: ${e.message}")
        } finally {
            tex?.let { safely { engine.destroyTexture(it) } }
        }
    }

    // TEST 4: TOCHNAYA KOPIYA tekuschego koda - DOLZHEN KRASHIT
    // Zapuskaj cherez runSingle(engine, mi, 4)
    private fun test4_CurrentCode(engine: Engine, mi: MaterialInstance?) {
        Log.w(TAG, "--- TEST 4: TOCHNAYA KOPIYA (MOZHET KRASHIT!) ---")
        var tex: Texture? = null
        try {
            val usage = Texture.Usage.SAMPLEABLE or
                    Texture.Usage.COLOR_ATTACHMENT or
                    Texture.Usage.UPLOADABLE or
                    Texture.Usage.GEN_MIPMAPPABLE
            Log.w(TAG, "  usage = $usage")

            val mipLevels = (kotlin.math.log2(1024f)).toInt() + 1
            tex = Texture.Builder()
                .width(1024).height(1024)
                .levels(mipLevels)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8)
                .usage(usage)
                .build(engine)

            val sampler = TextureSampler().apply {
                setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                setMagFilter(TextureSampler.MagFilter.LINEAR)
            }

            if (mi != null) {
                mi.setParameter("baseColorMap", tex, sampler)
                val bmp = createTestBitmap(1024)
                TextureHelper.setBitmap(engine, tex, 0, bmp)
                bmp.recycle()
                tex.generateMipmaps(engine)
                Log.w(TAG, "  PASSED 4 - tekuschij kod NE krashit?!")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "  FAIL 4 (OZHIDAEMO): ${e.message}")
        } finally {
            tex?.let { safely { engine.destroyTexture(it) } }
        }
    }

    // TEST 5: RGBA8 vmesto SRGB8_A8
    // Proverjaet: A6, E1
    private fun test5_Rgba8Format(engine: Engine, mi: MaterialInstance?) {
        Log.w(TAG, "--- TEST 5: RGBA8, S|U, 1024, 1 level ---")
        var tex: Texture? = null
        try {
            val usage = Texture.Usage.SAMPLEABLE or Texture.Usage.UPLOADABLE
            tex = Texture.Builder()
                .width(1024).height(1024)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8)
                .usage(usage)
                .build(engine)

            val bmp = createTestBitmap(1024)
            TextureHelper.setBitmap(engine, tex, 0, bmp)
            bmp.recycle()

            if (mi != null) {
                mi.setParameter("baseColorMap", tex, TextureSampler())
                Log.w(TAG, "  PASSED 5")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "  FAIL 5: ${e.message}")
        } finally {
            tex?.let { safely { engine.destroyTexture(it) } }
        }
    }

    // TEST 6: SRGB8_A8 + 1 mip level
    // Proverjaet: A7, B2, E1
    private fun test6_SingleMipLevel(engine: Engine, mi: MaterialInstance?) {
        Log.w(TAG, "--- TEST 6: SRGB8_A8, 1 level, S|U ---")
        var tex: Texture? = null
        try {
            val usage = Texture.Usage.SAMPLEABLE or Texture.Usage.UPLOADABLE
            tex = Texture.Builder()
                .width(1024).height(1024)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8)
                .usage(usage)
                .build(engine)

            val bmp = createTestBitmap(1024)
            TextureHelper.setBitmap(engine, tex, 0, bmp)
            bmp.recycle()

            if (mi != null) {
                val sampler = TextureSampler().apply {
                    setMinFilter(TextureSampler.MinFilter.LINEAR)
                    setMagFilter(TextureSampler.MagFilter.LINEAR)
                }
                mi.setParameter("baseColorMap", tex, sampler)
                Log.w(TAG, "  PASSED 6")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "  FAIL 6: ${e.message}")
        } finally {
            tex?.let { safely { engine.destroyTexture(it) } }
        }
    }

    // TEST 7: setBitmap PERED setParameter
    // Te zhe 4 flaga chto test 4, no drugoj poryadok
    // Proverjaet: B1, B3
    private fun test7_SetBitmapFirst(engine: Engine, mi: MaterialInstance?) {
        Log.w(TAG, "--- TEST 7: setBitmap -> mipmaps -> setParameter ---")
        var tex: Texture? = null
        try {
            val usage = Texture.Usage.SAMPLEABLE or
                    Texture.Usage.COLOR_ATTACHMENT or
                    Texture.Usage.UPLOADABLE or
                    Texture.Usage.GEN_MIPMAPPABLE
            val mipLevels = (kotlin.math.log2(1024f)).toInt() + 1

            tex = Texture.Builder()
                .width(1024).height(1024)
                .levels(mipLevels)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8)
                .usage(usage)
                .build(engine)

            val bmp = createTestBitmap(1024)
            TextureHelper.setBitmap(engine, tex, 0, bmp)
            bmp.recycle()
            tex.generateMipmaps(engine)

            if (mi != null) {
                val sampler = TextureSampler().apply {
                    setMinFilter(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR)
                    setMagFilter(TextureSampler.MagFilter.LINEAR)
                }
                mi.setParameter("baseColorMap", tex, sampler)
                Log.w(TAG, "  PASSED 7 - PORYADOK imeet znachenie!")
                Log.w(TAG, "  -> FIX: setParameter POSLE setBitmap+generateMipmaps")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "  FAIL 7: ${e.message}")
            Log.e(TAG, "  -> Poryadok NE pomog, problema v samih flagah")
        } finally {
            tex?.let { safely { engine.destroyTexture(it) } }
        }
    }

    // TEST 8: "Tupaya tekstura" - polnyj minimum
    // S|U, 1 level, SRGB8_A8, LINEAR sampler, bitmap first
    private fun test8_SimpleSamplerNoMipmap(engine: Engine, mi: MaterialInstance?) {
        Log.w(TAG, "--- TEST 8: Tupaya tekstura - polnyj minimum ---")
        var tex: Texture? = null
        try {
            val usage = Texture.Usage.SAMPLEABLE or Texture.Usage.UPLOADABLE
            tex = Texture.Builder()
                .width(1024).height(1024)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8)
                .usage(usage)
                .build(engine)

            val bmp = createTestBitmap(1024)
            TextureHelper.setBitmap(engine, tex, 0, bmp)
            bmp.recycle()

            if (mi != null) {
                val sampler = TextureSampler().apply {
                    setMinFilter(TextureSampler.MinFilter.LINEAR)
                    setMagFilter(TextureSampler.MagFilter.LINEAR)
                    setWrapModeS(TextureSampler.WrapMode.CLAMP_TO_EDGE)
                    setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE)
                }
                mi.setParameter("baseColorMap", tex, sampler)
                Log.w(TAG, "  PASSED 8 - tupaya tekstura RABOTAET!")
                Log.w(TAG, "  -> ISPOLZUJ ETOT PODHOD KAK FINALNYJ FIX")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "  FAIL 8: ${e.message}")
            Log.e(TAG, "  -> Dazhe minimum ne rabotaet! Problema v MI ili Engine")
        } finally {
            tex?.let { safely { engine.destroyTexture(it) } }
        }
    }

    // TEST 9: COLOR_ATTACHMENT bez GEN_MIPMAPPABLE
    // Izoliruet vinovnika. MOZHET KRASHIT.
    private fun test9_ColorAttachmentCombo(engine: Engine, mi: MaterialInstance?) {
        Log.w(TAG, "--- TEST 9: S|U|COLOR_ATTACHMENT (bez GEN_MIP) ---")
        var tex: Texture? = null
        try {
            val usage = Texture.Usage.SAMPLEABLE or
                    Texture.Usage.UPLOADABLE or
                    Texture.Usage.COLOR_ATTACHMENT
            Log.w(TAG, "  usage = $usage")

            tex = Texture.Builder()
                .width(1024).height(1024)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8)
                .usage(usage)
                .build(engine)

            val bmp = createTestBitmap(1024)
            TextureHelper.setBitmap(engine, tex, 0, bmp)
            bmp.recycle()

            if (mi != null) {
                mi.setParameter("baseColorMap", tex, TextureSampler())
                Log.w(TAG, "  PASSED 9 - COLOR_ATTACHMENT OK")
                Log.w(TAG, "  -> Vinovat GEN_MIPMAPPABLE!")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "  FAIL 9: ${e.message}")
            Log.e(TAG, "  -> COLOR_ATTACHMENT vinovat! Ubiraj ego")
        } finally {
            tex?.let { safely { engine.destroyTexture(it) } }
        }
    }

    // --- Helpers ---

    private fun createTestBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.RED)
        return bmp
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }
}

/*
 * KAK PODKLYUCHIT:
 *
 * V ModelEditorScreen.kt, v LaunchedEffect(modelInstance):
 *
 *   LaunchedEffect(modelInstance) {
 *       val mi = modelInstance ?: return@LaunchedEffect
 *       if (!scanned) {
 *           elements = editor.scanModel(engine, mi)
 *           scanned = true
 *
 *           // == DIAGNOSTIKA ==
 *           val headMI = elements
 *               .firstOrNull { it.meshName == "head_lod0_ORIGINAL" }
 *               ?.materialInstance
 *           TextureCrashDiagnostic.runAll(engine, headMI)
 *       }
 *   }
 *
 *
 * DEREVO RESHENIJ (chitaj posle Logcat):
 *
 * Test 0: Smotri ordinal znacheniya
 *   SAMPLEABLE.ordinal == 0?
 *     DA -> or teryaet bit. Nuzhen raw int.
 *     NET -> Smotri dalshe
 *
 * Test 1 OK + Test 4 FAIL
 *   -> Problema v usage flagah
 *      Test 3 OK + Test 9 FAIL -> ubiraj COLOR_ATTACHMENT
 *      Test 3 OK + Test 9 OK  -> ubiraj GEN_MIPMAPPABLE
 *      Test 3 FAIL             -> ubiraj UPLOADABLE
 *
 * Test 1 OK + Test 7 OK (a Test 4 FAIL)
 *   -> Problema v PORYADKE: setParameter do setBitmap
 *   -> FIX: perestav stroki v uploadTextureToGpu
 *
 * Test 8 OK (a ostalnie FAIL)
 *   -> Ispolzuj "tupuyu teksturu" kak finalnyj FIX
 *   -> Mipmap ne nuzhny dlya 1024 face texture
 *
 * Test 1 FAIL
 *   -> Problema v MaterialInstance ili Engine lifecycle
 *   -> Poprobuj bez patcha GLB (ensureSourceInCache)
 *
 * VSE testy OK
 *   -> Problema v threading (gonka potokov)
 *   -> Uberi postGpuOp, vyzyvaj upload sinhronno
 *   -> Ili perenesi upload v onFrame kolbek SceneView
 */
