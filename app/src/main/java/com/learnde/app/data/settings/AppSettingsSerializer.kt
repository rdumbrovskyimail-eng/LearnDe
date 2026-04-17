package com.learnde.app.data.settings

import android.util.Log
import androidx.datastore.core.Serializer
import com.learnde.app.data.security.CryptoManager
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * DataStore Serializer с прозрачным AES-256-GCM шифрованием.
 * При любых сбоях (повреждённый ключ, corrupt файл) — возвращает defaultValue
 * вместо падения.
 */
class AppSettingsSerializer @Inject constructor(
    private val cryptoManager: CryptoManager
) : Serializer<AppSettings> {

    companion object {
        private const val TAG = "AppSettingsSerializer"
    }

    override val defaultValue: AppSettings = AppSettings()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun readFrom(input: InputStream): AppSettings {
        return try {
            val encryptedBytes = input.readBytes()
            if (encryptedBytes.isEmpty()) return defaultValue
            val decrypted = cryptoManager.decrypt(encryptedBytes).decodeToString()
            json.decodeFromString(AppSettings.serializer(), decrypted)
        } catch (e: SerializationException) {
            Log.w(TAG, "readFrom: serialization failed — using defaults (${e.message})")
            defaultValue
        } catch (e: Exception) {
            Log.w(TAG, "readFrom: crypto/IO failed — using defaults (${e.message})")
            defaultValue
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        try {
            val jsonString = json.encodeToString(AppSettings.serializer(), t)
            val encryptedBytes = cryptoManager.encrypt(jsonString.encodeToByteArray())
            output.write(encryptedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "writeTo: encrypt/write failed (${e.message}) — data not persisted", e)
            // НЕ пробрасываем исключение — приложение не должно крашиться,
            // пользователь продолжит работу в памяти, настройки просто не сохранятся.
        }
    }
}