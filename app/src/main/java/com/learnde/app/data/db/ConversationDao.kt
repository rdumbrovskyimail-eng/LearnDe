// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/data/db/ConversationDao.kt
//
// ФИКС: appendOrAdd теперь проверяет совпадение sessionId. Без этого
// первое сообщение новой сессии дописывалось к последнему сообщению
// предыдущей сессии (склеивались в простыню через reconnect).
// ═══════════════════════════════════════════════════════════
package com.learnde.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO для persistent истории разговоров.
 * Поддерживает appendOrAdd (стриминг ответов модели),
 * поиск по тексту, лимитирование по количеству.
 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY timestamp ASC")
    fun getAllFlow(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY timestamp ASC")
    suspend fun getAll(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: String): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE text LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 50): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversationEntity): Long

    @Update
    suspend fun update(entity: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(): ConversationEntity?

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getCount(): Int

    @Query("DELETE FROM conversations")
    suspend fun clearAll()

    @Query("DELETE FROM conversations WHERE id IN (SELECT id FROM conversations ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    /**
     * Если последнее сообщение ОТ ТОЙ ЖЕ СЕССИИ И той же роли — дописать текст
     * (стриминг). Иначе — создать новое. Ограничиваем общее количество сообщений.
     */
    @Transaction
    suspend fun appendOrAdd(role: String, text: String, sessionId: String = "", maxMessages: Int = 200) {
        val last = getLastMessage()
        if (last != null && last.role == role && last.sessionId == sessionId) {
            update(last.copy(text = last.text + text))
        } else {
            insert(ConversationEntity(role = role, text = text, sessionId = sessionId))
        }
        val count = getCount()
        if (count > maxMessages) {
            deleteOldest(count - maxMessages)
        }
    }
}
