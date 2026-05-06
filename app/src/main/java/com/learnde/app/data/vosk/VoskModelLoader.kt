// ═══════════════════════════════════════════════════════════
// Путь: app/src/main/java/com/learnde/app/data/vosk/VoskModelLoader.kt
//
// Загружает Vosk-модели (RU + DE) из APK assets во внутренний кэш
// приложения и инициализирует Model-инстансы.
//
// Модели в /assets/vosk/ru/ и /assets/vosk/de/ копируются в
// context.filesDir/vosk-models/ru и .../de один раз. При следующих
// запусках проверяется маркер-файл .ready и распаковка пропускается.
//
// Жизненный цикл:
//   1. ensureModelsExtracted() — проверка/распаковка ассетов
//   2. loadModels() — создание Model-инстансов (~150 МБ RAM на каждую)
//   3. release() — освобождение памяти при закрытии translator-сессии
// ═══════════════════════════════════════════════════════════
package com.learnde.app.data.vosk

import android.content.Context
import com.learnde.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoskModelLoader @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val logger: AppLogger,
) {

    companion object {
        private const val ASSETS_VOSK_DIR = "vosk"
        private const val MODELS_DIR_NAME = "vosk-models"
        private const val READY_MARKER = ".ready"

        // Версия бампается если меняется набор моделей или структура.
        // При несовпадении версии — модели распаковываются заново.
        private const val MODELS_VERSION = "ru-0.22+de-0.15"
    }

    init {
        // Тише натив-логи Vosk (он по дефолту шумный).
        // Выводим только WARN и ERROR.
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
    }

    @Volatile private var modelRu: Model? = null
    @Volatile private var modelDe: Model? = null

    /**
     * Готовит обе модели к использованию.
     * Безопасно вызывать многократно — повторный вызов сразу вернётся
     * если модели уже загружены.
     *
     * @return Pair<RU, DE>
     * @throws Exception при ошибке распаковки или загрузки
     */
    suspend fun loadModels(): Pair<Model, Model> = withContext(Dispatchers.IO) {
        modelRu?.let { ru ->
            modelDe?.let { de ->
                logger.d("VoskModelLoader: models already loaded, reusing")
                return@withContext ru to de
            }
        }

        logger.d("VoskModelLoader: ensuring models extracted…")
        val ruDir = ensureModelExtracted("ru")
        val deDir = ensureModelExtracted("de")

        logger.d("VoskModelLoader: loading RU model from ${ruDir.absolutePath}")
        val ru = runCatching { Model(ruDir.absolutePath) }
            .getOrElse { e ->
                logger.e("VoskModelLoader: failed to load RU model: ${e.message}", e)
                throw IllegalStateException("Cannot load Vosk RU model: ${e.message}", e)
            }

        logger.d("VoskModelLoader: loading DE model from ${deDir.absolutePath}")
        val de = runCatching { Model(deDir.absolutePath) }
            .getOrElse { e ->
                logger.e("VoskModelLoader: failed to load DE model: ${e.message}", e)
                runCatching { ru.close() }
                throw IllegalStateException("Cannot load Vosk DE model: ${e.message}", e)
            }

        modelRu = ru
        modelDe = de
        logger.d("VoskModelLoader: ✓ both models loaded")
        ru to de
    }

    /**
     * Освобождает память моделей. Вызвать при закрытии translator-сессии
     * если хочешь высвободить ~300 МБ RAM. Иначе модели держатся в
     * памяти пока живёт процесс приложения (это синглтон).
     */
    fun release() {
        runCatching { modelRu?.close() }
        runCatching { modelDe?.close() }
        modelRu = null
        modelDe = null
        logger.d("VoskModelLoader: released")
    }

    // ════════════════════════════════════════════════════════════
    //  PRIVATE — extraction
    // ════════════════════════════════════════════════════════════

    /**
     * Распаковывает одну модель из assets/vosk/<lang> в filesDir/vosk-models/<lang>.
     * При наличии корректного маркера .ready — пропускает.
     */
    private fun ensureModelExtracted(lang: String): File {
        val targetDir = File(appContext.filesDir, "$MODELS_DIR_NAME/$lang")
        val markerFile = File(targetDir, READY_MARKER)

        // Проверка корректности существующей распаковки
        if (markerFile.exists()) {
            val markerContent = runCatching { markerFile.readText() }.getOrNull()
            if (markerContent == MODELS_VERSION) {
                // Дополнительная проверка ключевых файлов (на случай частичной поломки)
                val criticalFiles = listOf(
                    "am/final.mdl",
                    "conf/model.conf",
                    "graph/HCLr.fst",
                    "ivector/final.ie",
                )
                val allExist = criticalFiles.all { File(targetDir, it).exists() }
                if (allExist) {
                    logger.d("VoskModelLoader: $lang already extracted, skipping")
                    return targetDir
                }
                logger.w("VoskModelLoader: $lang marker present but files missing, re-extracting")
            } else {
                logger.d("VoskModelLoader: $lang version mismatch ($markerContent → $MODELS_VERSION), re-extracting")
            }
            // Чистим старую распаковку перед перезаписью
            targetDir.deleteRecursively()
        }

        targetDir.mkdirs()
        val sourcePath = "$ASSETS_VOSK_DIR/$lang"
        val startedAt = System.currentTimeMillis()
        val totalBytes = copyAssetDir(sourcePath, targetDir)
        val elapsed = System.currentTimeMillis() - startedAt
        logger.d("VoskModelLoader: $lang extracted ($totalBytes bytes in ${elapsed}ms)")

        // Записываем маркер ПОСЛЕ успешной распаковки
        markerFile.writeText(MODELS_VERSION)
        return targetDir
    }

    /**
     * Рекурсивно копирует папку из APK assets во внутреннее хранилище.
     * Возвращает суммарный размер скопированных файлов.
     */
    private fun copyAssetDir(assetPath: String, targetDir: File): Long {
        val assetManager = appContext.assets
        val children = runCatching { assetManager.list(assetPath) }.getOrNull()
            ?: return 0L

        if (children.isEmpty()) {
            // Это файл, не директория — копируем как файл
            return copyAssetFile(assetPath, targetDir)
        }

        targetDir.mkdirs()
        var total = 0L
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childTarget = File(targetDir, child)

            // Проверяем — папка это или файл
            val grandchildren = runCatching { assetManager.list(childAssetPath) }.getOrNull()
            if (grandchildren.isNullOrEmpty()) {
                total += copyAssetFile(childAssetPath, childTarget)
            } else {
                total += copyAssetDir(childAssetPath, childTarget)
            }
        }
        return total
    }

    private fun copyAssetFile(assetPath: String, targetFile: File): Long {
        targetFile.parentFile?.mkdirs()
        appContext.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile).use { output ->
                return input.copyTo(output)
            }
        }
    }
}