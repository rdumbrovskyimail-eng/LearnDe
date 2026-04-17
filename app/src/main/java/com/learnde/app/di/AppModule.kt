package com.learnde.app.di

import com.learnde.app.data.AndroidAudioEngine
import com.learnde.app.data.GeminiLiveClient
import com.learnde.app.data.PersistentConversationRepository
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.ConversationRepository
import com.learnde.app.domain.LiveClient
import com.learnde.app.learn.core.VoiceScope
import com.learnde.app.util.AppLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Биндинг абстракций на реализации (только @Binds — должен быть abstract class).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingModule {

    @Binds
    @Singleton
    abstract fun bindConversationRepository(
        impl: PersistentConversationRepository
    ): ConversationRepository
}

/**
 * Voice-специфичные инстансы (@Provides — должен быть object).
 * Квалификатор @VoiceScope отделяет их от @LearnScope.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceProvidesModule {

    @Provides
    @Singleton
    @VoiceScope
    fun provideVoiceLiveClient(
        logger: AppLogger
    ): LiveClient = GeminiLiveClient(logger)

    @Provides
    @Singleton
    @VoiceScope
    fun provideVoiceAudioEngine(
        logger: AppLogger
    ): AudioEngine = AndroidAudioEngine(logger)
}