// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА (Patch 3)
// Путь: app/src/main/java/com/learnde/app/learn/data/db/A1Database.kt
//
// ИЗМЕНЕНИЯ:
//   - version = 3 (было 2)
//   - Добавлена MIGRATION_2_3 для FSRS-полей в a1_lemmas
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 5, // v3.5.1: Очистка дублей лемм и артиклей
    exportSchema = false
)
@TypeConverters(A1Converters::class)
abstract class A1Database : RoomDatabase() {
    abstract fun lemmaDao(): A1LemmaDao
    abstract fun clusterDao(): A1ClusterDao
    abstract fun grammarDao(): A1GrammarDao
    abstract fun sessionDao(): A1SessionDao
    abstract fun userProgressDao(): A1UserProgressDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE a1_session_logs ADD COLUMN isComplete INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE a1_session_logs ADD COLUMN phaseReached TEXT NOT NULL DEFAULT 'COOL_DOWN'")
        db.execSQL("ALTER TABLE a1_session_logs ADD COLUMN errorDiagnosesJson TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("ALTER TABLE a1_session_logs ADD COLUMN avgQuality REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE a1_session_logs ADD COLUMN evaluateCallsCount INTEGER NOT NULL DEFAULT 0")
    }
}

/** Patch 3: FSRS-5 fields. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE a1_lemmas ADD COLUMN fsrsDifficulty REAL NOT NULL DEFAULT 5.0")
        db.execSQL("ALTER TABLE a1_lemmas ADD COLUMN fsrsStability REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE a1_lemmas ADD COLUMN fsrsReps INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE a1_lemmas ADD COLUMN fsrsLapses INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE a1_lemmas ADD COLUMN fsrsLastReviewAt INTEGER NOT NULL DEFAULT 0")
    }
}

/** v3.1.2: grammarRuleId для точной привязки кластера к правилу. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE a1_clusters ADD COLUMN grammarRuleId TEXT DEFAULT NULL")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Удаляем явный мусор с артиклями
        db.execSQL("DELETE FROM a1_lemmas WHERE lemma LIKE 'der %' OR lemma LIKE 'die %' OR lemma LIKE 'das %'")
        // Удаляем дубли в нижнем регистре, если есть нормальная версия
        db.execSQL("""
            DELETE FROM a1_lemmas 
            WHERE lemma = LOWER(lemma) 
            AND pos = 'Substantiv' 
            AND EXISTS (
                SELECT 1 FROM a1_lemmas AS t2 
                WHERE LOWER(t2.lemma) = a1_lemmas.lemma 
                AND t2.lemma != a1_lemmas.lemma
            )
        """)
    }
}

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

@Module
@InstallIn(SingletonComponent::class)
object A1DatabaseModule {

    @Provides
    @Singleton
    fun provideA1Database(@ApplicationContext ctx: Context): A1Database =
        Room.databaseBuilder(ctx, A1Database::class.java, "a1_learning.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun provideLemmaDao(db: A1Database) = db.lemmaDao()
    @Provides fun provideClusterDao(db: A1Database) = db.clusterDao()
    @Provides fun provideGrammarDao(db: A1Database) = db.grammarDao()
    @Provides fun provideSessionDao(db: A1Database) = db.sessionDao()
    @Provides fun provideUserProgressDao(db: A1Database) = db.userProgressDao()
}