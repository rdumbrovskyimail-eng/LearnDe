package com.codeextractor.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.codeextractor.app.domain.model.ConversationMessage

/**
 * Room entity для persistent хранения истории разговоров.
 * Индексы по timestamp и role для быстрых запросов.
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["role"]),
        Index(value = ["sessionId"])
    ]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String = "",
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromMessage(msg: ConversationMessage, sessionId: String = "") =
            ConversationEntity(
                sessionId = sessionId,
                role = msg.role,
                text = msg.text,
                timestamp = msg.timestamp
            )
    }

    fun toMessage() = ConversationMessage(
        role = role,
        text = text,
        timestamp = timestamp
    )
}
