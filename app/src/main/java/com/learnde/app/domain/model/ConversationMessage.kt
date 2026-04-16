package com.learnde.app.domain.model

/**
 * Одно сообщение в истории диалога.
 * Используется для:
 * 1. Отображения в UI (VoiceScreen → transcript)
 * 2. Context restore при reconnect (отправка через clientContent)
 * 3. Будущее: сохранение в Room (Этап 7)
 */
data class ConversationMessage(
    /** "user" или "model" — совпадает с Gemini API roles */
    val role: String,

    /** Текст (транскрипция или ответ модели) */
    val text: String,

    /** Timestamp в миллисекундах */
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_MODEL = "model"

        fun user(text: String) = ConversationMessage(ROLE_USER, text)
        fun model(text: String) = ConversationMessage(ROLE_MODEL, text)
    }
}