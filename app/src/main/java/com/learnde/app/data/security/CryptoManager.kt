package com.learnde.app.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
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
 */
@Singleton
class CryptoManager @Inject constructor() {

    companion object {
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

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey =
        keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: generateKey()

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM, ANDROID_KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(BLOCK_MODE)
            .setEncryptionPaddings(PADDING)
            .setKeySize(KEY_SIZE)

        // StrongBox → TEE fallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                keyGenerator.init(specBuilder.setIsStrongBoxBacked(true).build())
                return keyGenerator.generateKey()
            } catch (_: StrongBoxUnavailableException) {
                // Нет выделенного чипа → используем TEE (ARM TrustZone)
            }
        }

        keyGenerator.init(specBuilder.build())
        return keyGenerator.generateKey()
    }

    fun encrypt(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv + cipher.doFinal(plainBytes)
    }

    fun decrypt(cipherBytes: ByteArray): ByteArray {
        require(cipherBytes.size > GCM_IV_SIZE) {
            "Encrypted payload too short: ${cipherBytes.size} bytes"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv            = cipherBytes.copyOfRange(0, GCM_IV_SIZE)
        val encryptedData = cipherBytes.copyOfRange(GCM_IV_SIZE, cipherBytes.size)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LEN, iv))
        return cipher.doFinal(encryptedData)
    }
}