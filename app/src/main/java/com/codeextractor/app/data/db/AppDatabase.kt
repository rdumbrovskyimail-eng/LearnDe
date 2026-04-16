// ═══════════════════════════════════════════════════════════
// ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/data/db/AppDatabase.kt
// Изменения: exportSchema = false (не нужна конфигурация schema dir)
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        const val DATABASE_NAME = "copym_conversations.db"
    }
}