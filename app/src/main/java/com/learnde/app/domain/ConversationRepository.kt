// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/codeextractor/app/domain/ConversationRepository.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.domain

import com.learnde.app.domain.model.ConversationMessage
import kotlinx.coroutines.flow.Flow

/**
 * Абстракция хранилища истории разговора.
 *
 * Контракт:
 *  - add()         — добавить сообщение
 *  - appendOrAdd() — дописать текст к последнему (стриминг) или создать новое
 *  - getAll()      — все сообщения (timestamp ASC)
 *  - getAllFlow()   — реактивный Flow всех сообщений
 *  - search()      — полнотекстовый поиск
 *  - clear()       — очистка всей истории
 *
 * Реализация: PersistentConversationRepository (Room)
 */
interface ConversationRepository {
    suspend fun add(message: ConversationMessage)
    suspend fun appendOrAdd(role: String, text: String)
    suspend fun getAll(): List<ConversationMessage>
    fun getAllFlow(): Flow<List<ConversationMessage>>
    suspend fun search(query: String): List<ConversationMessage>
    suspend fun clear()
}