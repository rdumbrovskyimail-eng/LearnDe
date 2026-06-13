package com.learnde.app.learn.data.db.v2

import androidx.room.Dao
import androidx.room.Query

@Dao
interface LearnerProfileDao {
    @Query("SELECT * FROM a1_learner_profile WHERE id = :id LIMIT 1")
    suspend fun get(id: String = "default"): LearnerProfileEntity?

    @Query("UPDATE a1_learner_profile SET displayName = :name, updatedAt = :now WHERE id = :id")
    suspend fun updateName(name: String, id: String = "default", now: Long = System.currentTimeMillis())

    @Query("UPDATE a1_learner_profile SET interestsCsv = :interests, updatedAt = :now WHERE id = :id")
    suspend fun updateInterests(interests: String, id: String = "default", now: Long = System.currentTimeMillis())

    @Query("UPDATE a1_learner_profile SET totalBlindSessions = totalBlindSessions + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementBlindSessions(id: String = "default", now: Long = System.currentTimeMillis())
}