package com.codeextractor.app.data

import com.codeextractor.app.domain.model.ConversationMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Потокобезопасное хранилище истории разговора (in-memory).
 * Используется для context restore при reconnect и для UI.
 * Будущее: Этап 7 — миграция на Room.
 */
@Singleton
class InMemoryConversationStore @Inject constructor() {

    companion object {
        private const val MAX_MESSAGES = 50
    }

    private val messages = ArrayDeque<ConversationMessage>(MAX_MESSAGES + 2)

    fun add(message: ConversationMessage) {
        synchronized(messages) {
            messages.addLast(message)
            while (messages.size > MAX_MESSAGES) {
                messages.removeFirst()
            }
        }
    }

    /**
     * Если последнее сообщение от той же роли — дописать текст.
     * Иначе — создать новое. Нужно для стриминговых ответов модели,
     * где каждый чанк транскрипции приходит отдельным событием.
     */
    fun appendOrAdd(role: String, text: String) {
        synchronized(messages) {
            val last = messages.lastOrNull()
            if (last != null && last.role == role) {
                messages.removeLast()
                messages.addLast(last.copy(text = last.text + text))
            } else {
                val msg = ConversationMessage(role, text)
                messages.addLast(msg)
                while (messages.size > MAX_MESSAGES) {
                    messages.removeFirst()
                }
            }
        }
    }

    fun getAll(): List<ConversationMessage> = synchronized(messages) {
        messages.toList()
    }

    fun clear() = synchronized(messages) {
        messages.clear()
    }
}