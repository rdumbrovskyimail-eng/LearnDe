// ═══════════════════════════════════════════════════════════
// ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/data/PersistentConversationRepository.kt
// Изменения: startNewSession() вызывается из ConversationRepository интерфейса
// ═══════════════════════════════════════════════════════════
package com.learnde.app.data

import com.learnde.app.data.db.ConversationDao
import com.learnde.app.data.db.ConversationEntity
import com.learnde.app.domain.ConversationRepository
import com.learnde.app.domain.model.ConversationMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersistentConversationRepository @Inject constructor(
    private val dao: ConversationDao
) : ConversationRepository {

    companion object {
        private const val MAX_MESSAGES = 200
    }

    @Volatile
    private var currentSessionId: String = System.currentTimeMillis().toString()

    /** Начинает новую сессию. Вызывать при каждом handleConnect(). */
    fun startNewSession() {
        currentSessionId = System.currentTimeMillis().toString()
    }

    override suspend fun add(message: ConversationMessage) {
        dao.insert(ConversationEntity.fromMessage(message, currentSessionId))
        trimIfNeeded()
    }

    override suspend fun appendOrAdd(role: String, text: String) {
        dao.appendOrAdd(role, text, currentSessionId, MAX_MESSAGES)
    }

    override suspend fun getAll(): List<ConversationMessage> =
        dao.getAll().map { it.toMessage() }

    override fun getAllFlow(): Flow<List<ConversationMessage>> =
        dao.getAllFlow().map { list -> list.map { it.toMessage() } }

    override suspend fun search(query: String): List<ConversationMessage> =
        dao.search(query).map { it.toMessage() }

    override suspend fun clear() {
        dao.clearAll()
    }

    private suspend fun trimIfNeeded() {
        val count = dao.getCount()
        if (count > MAX_MESSAGES) {
            dao.deleteOldest(count - MAX_MESSAGES)
        }
    }
}