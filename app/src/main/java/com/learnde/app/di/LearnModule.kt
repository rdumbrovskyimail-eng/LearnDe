// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/di/LearnModule.kt
//
// Hilt-модуль для автономного Learn-стека.
// Предоставляет отдельные @LearnScope инстансы LiveClient и AudioEngine.
//
// ВАЖНО: должен идти ПОСЛЕ AppModule, и GeminiLiveClient/AndroidAudioEngine
// больше НЕ должны быть помечены @Singleton на самих классах — только
// здесь и в AppModule через @Provides.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.di

import android.content.Context
import com.learnde.app.data.AndroidAudioEngine
import com.learnde.app.data.GeminiLiveClient
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.LiveClient
import com.learnde.app.learn.core.LearnScope
import com.learnde.app.util.AppLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LearnModule {

    @Provides
    @Singleton
    @LearnScope
    fun provideLearnLiveClient(logger: AppLogger): LiveClient =
        GeminiLiveClient(logger)

    @Provides
    @Singleton
    @LearnScope
    fun provideLearnAudioEngine(
        @ApplicationContext ctx: Context,
        logger: AppLogger,
        // ← если AndroidAudioEngine требует больше зависимостей, добавь их здесь
    ): AudioEngine = AndroidAudioEngine(ctx, logger)
}