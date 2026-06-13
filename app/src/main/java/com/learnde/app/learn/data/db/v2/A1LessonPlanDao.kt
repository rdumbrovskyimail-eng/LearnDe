package com.learnde.app.learn.data.db.v2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface A1LessonPlanDao {
    @Query("UPDATE a1_lesson_plans SET isFinished = 1")
    suspend fun markAllFinished()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: LessonPlanStateEntity)

    @Query("SELECT * FROM a1_lesson_plans WHERE isFinished = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActivePlan(): LessonPlanStateEntity?

    @Query("UPDATE a1_lesson_plans SET isFinished = 1 WHERE planId = :planId")
    suspend fun markFinished(planId: String)
}