// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/data/db/A1Database.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Database(
    entities = [
        LemmaA1Entity::class,
        ClusterA1Entity::class,
        GrammarRuleA1Entity::class,
        A1SessionLogEntity::class,
        A1UserProgressEntity::class,
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(A1Converters::class)
abstract class A1Database : RoomDatabase() {
    abstract fun lemmaDao(): A1LemmaDao
    abstract fun clusterDao(): A1ClusterDao
    abstract fun grammarDao(): A1GrammarDao
    abstract fun sessionDao(): A1SessionDao
    abstract fun userProgressDao(): A1UserProgressDao
}

/**
 * Room TypeConverters. В таблицах мы храним массивы как JSON-строки —
 * это проще чем вводить связующие таблицы для коллекций строк.
 */
class A1Converters {
    @TypeConverter
    fun listToJson(list: List<String>?): String =
        if (list == null) "[]" else Json.encodeToString(list)

    @TypeConverter
    fun jsonToList(json: String?): List<String> =
        if (json.isNullOrBlank()) emptyList()
        else try {
            Json.decodeFromString<List<String>>(json)
        } catch (e: Exception) { emptyList() }
}

// ════════════════════════════════════════════════════
//  HILT MODULE
// ════════════════════════════════════════════════════
@Module
@InstallIn(SingletonComponent::class)
object A1DatabaseModule {

    @Provides
    @Singleton
    fun provideA1Database(@ApplicationContext ctx: Context): A1Database =
        Room.databaseBuilder(ctx, A1Database::class.java, "a1_learning.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideLemmaDao(db: A1Database) = db.lemmaDao()
    @Provides fun provideClusterDao(db: A1Database) = db.clusterDao()
    @Provides fun provideGrammarDao(db: A1Database) = db.grammarDao()
    @Provides fun provideSessionDao(db: A1Database) = db.sessionDao()
    @Provides fun provideUserProgressDao(db: A1Database) = db.userProgressDao()
}