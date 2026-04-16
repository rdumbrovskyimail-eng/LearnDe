package com.codeextractor.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Хранилище PNG-фона для режима CUSTOM_IMAGE сцены.
 *
 * - Копирует выбранный из галереи файл в internalFilesDir/scene_bg.png
 *   (чтобы переживать перезагрузку и не зависеть от SAF-URI).
 * - Отдаёт Bitmap через StateFlow для Compose.
 * - Максимум 2560x2560 — для предотвращения OOM.
 */
@Singleton
class BackgroundImageStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val FILE_NAME = "scene_bg.png"
        private const val MAX_DIM   = 2560
    }

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    private val _bitmap = MutableStateFlow<Bitmap?>(loadFromDisk())
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    val hasImage: Boolean get() = file.exists()

    /** Импорт PNG из галерей (Uri → internalFiles). Возвращает true при успехе. */
    suspend fun importFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val sample = calcInSampleSize(opts.outWidth, opts.outHeight)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return@withContext false

            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            _bitmap.value = bmp
            true
        } catch (e: Exception) { false }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        _bitmap.value = null
    }

    private fun loadFromDisk(): Bitmap? = runCatching {
        if (!file.exists()) null
        else BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun calcInSampleSize(w: Int, h: Int): Int {
        var sample = 1
        var half = maxOf(w, h)
        while (half / sample > MAX_DIM) sample *= 2
        return sample
    }
}