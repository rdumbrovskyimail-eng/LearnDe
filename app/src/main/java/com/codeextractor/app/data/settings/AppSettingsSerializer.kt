package com.codeextractor.app.data.settings

import androidx.datastore.core.Serializer
import com.codeextractor.app.data.security.CryptoManager
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * DataStore Serializer с прозрачным AES-256-GCM шифрованием.
 *
 * Запись: AppSettings → JSON → ByteArray → encrypt(Keystore) → диск
 * Чтение: диск → decrypt(Keystore) → ByteArray → JSON → AppSettings
 *
 * При повреждении файла или сбросе Keystore → defaultValue.
 */
class AppSettingsSerializer @Inject constructor(
    private val cryptoManager: CryptoManager
) : Serializer<AppSettings> {

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
        } catch (_: SerializationException) {
            defaultValue
        } catch (_: Exception) {
            defaultValue
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        val jsonString     = json.encodeToString(AppSettings.serializer(), t)
        val encryptedBytes = cryptoManager.encrypt(jsonString.encodeToByteArray())
        output.write(encryptedBytes)
    }
}