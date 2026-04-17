// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/data/security/CryptoManager.kt
//
// ФИКС:
//   • getOrCreateKey: обернули в runCatching. При любой ошибке
//     получения ключа (повреждён после update OS, сбой биометрии,
//     KeyPermanentlyInvalidatedException) — удаляем старый entry
//     и генерируем новый.
//   • Последствие: старые зашифрованные настройки станут нечитаемыми
//     → DataStore вернёт defaultValue. Это лучше, чем краш приложения
//     при каждом запуске.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM шифрование через Android Keystore.
 *
 * Стратегия выбора аппаратного хранилища:
 *   1. StrongBox (выделенный чип) — Pixel 3+, Samsung Galaxy S-серия
 *   2. TEE (ARM TrustZone) — fallback для остальных устройств
 *
 * Ключ НИКОГДА не покидает Secure Enclave.
 * Формат зашифрованного блока: [12 байт IV][данные + 16 байт GCM Auth Tag]
 *
 * Recovery: если ключ повреждён (например, после обновления ОС или
 * сброса биометрии) — удаляем старый и генерируем новый. Старые
 * зашифрованные данные становятся нечитаемыми; DataStore вернёт
 * defaultValue — приложение не крашится.
 */
@Singleton
class CryptoManager @Inject constructor() {

    companion object {
        private const val TAG               = "CryptoManager"
        private const val ANDROID_KEYSTORE  = "AndroidKeyStore"
        private const val ALGORITHM         = KeyProperties.KEY_ALGORITHM_AES
        private const val BLOCK_MODE        = KeyProperties.BLOCK_MODE_GCM
        private const val PADDING           = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val TRANSFORMATION    = "$ALGORITHM/$BLOCK_MODE/$PADDING"
        private const val KEY_ALIAS         = "codeextractor_app_key_v1"
        private const val GCM_IV_SIZE       = 12
        private const val GCM_TAG_LEN       = 128
        private const val KEY_SIZE          = 256
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Возвращает валидный SecretKey. Если существующий ключ повреждён —
     * удаляет его и создаёт новый.
     */
    private fun getOrCreateKey(): SecretKey {
        val existing = runCatching {
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        }.getOrNull()

        if (existing != null) return existing

        // Ключа нет или он повреждён — удаляем entry (на всякий случай) и генерируем новый.
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM, ANDROID_KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(BLOCK_MODE)
            .setEncryptionPaddings(PADDING)
            .setKeySize(KEY_SIZE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                keyGenerator.init(specBuilder.setIsStrongBoxBacked(true).build())
                return keyGenerator.generateKey()
            } catch (_: StrongBoxUnavailableException) {
                Log.d(TAG, "StrongBox unavailable, falling back to TEE")
            } catch (e: Exception) {
                Log.w(TAG, "StrongBox init failed: ${e.message}, falling back to TEE")
            }
        }

        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    /**
     * Регенерация ключа в случае фатального повреждения.
     * Приватный — вызывается только при retry в decrypt().
     */
    private fun regenerateKey(): SecretKey {
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
        return generateKey()
    }

    fun encrypt(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        } catch (e: Exception) {
            Log.w(TAG, "encrypt init failed: ${e.message} — regenerating key")
            cipher.init(Cipher.ENCRYPT_MODE, regenerateKey())
        }
        return cipher.iv + cipher.doFinal(plainBytes)
    }

    fun decrypt(cipherBytes: ByteArray): ByteArray {
        require(cipherBytes.size > GCM_IV_SIZE) {
            "Encrypted payload too short: ${cipherBytes.size} bytes"
        }
        val iv            = cipherBytes.copyOfRange(0, GCM_IV_SIZE)
        val encryptedData = cipherBytes.copyOfRange(GCM_IV_SIZE, cipherBytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LEN, iv))
        return cipher.doFinal(encryptedData)
    }
}
