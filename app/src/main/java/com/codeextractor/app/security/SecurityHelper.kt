package com.codeextractor.app.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Безопасное хранилище API-ключа.
 * Использует AES-256-GCM через AndroidKeyStore.
 *
 * Заменяет функции MainActivity.getPrefs() / loadApiKey() / saveApiKey().
 *
 * Миграция: при первом запуске после замены ключ потеряется
 * (старый файл незашифрован). Пользователь введёт ключ заново — это нормально.
 */
object SecurityHelper {
    private const val TAG = "SecurityHelper"
    private const val PREFS_FILE = "secure_prefs"
    private const val PREFS_KEY_API = "gemini_api_key"

    private fun buildEncryptedPrefs(context: Context) = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    fun loadApiKey(context: Context): String = runCatching {
        buildEncryptedPrefs(context)?.getString(PREFS_KEY_API, "").orEmpty()
    }.getOrDefault("").also { key ->
        if (key.isNotEmpty()) {
            Log.d(TAG, "API key loaded from EncryptedSharedPreferences (AES-256-GCM)")
        }
    }

    fun saveApiKey(context: Context, key: String) {
        runCatching {
            buildEncryptedPrefs(context)?.edit()?.putString(PREFS_KEY_API, key)?.apply()
            Log.d(TAG, "API key saved to EncryptedSharedPreferences (AES-256-GCM)")
        }.onFailure { e ->
            Log.e(TAG, "ESP save failed: ${e.message} — falling back to MODE_PRIVATE")
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putString(PREFS_KEY_API, key).apply()
        }
    }
}