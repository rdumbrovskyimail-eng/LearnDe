package com.learnde.app.di

import com.learnde.app.data.AndroidAudioEngine
import com.learnde.app.data.GeminiLiveClient
import com.learnde.app.data.PersistentConversationRepository
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.ConversationRepository
import com.learnde.app.domain.LiveClient
import com.learnde.app.util.AppLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    // ConversationRepository — @Binds, класс по-прежнему @Inject constructor
    @Binds
    @Singleton
    abstract fun bindConversationRepository(
        impl: PersistentConversationRepository
    ): ConversationRepository

    companion object {

        // ─── LiveClient: Voice (дефолтный, без квалификатора) ───
        @Provides
        @Singleton
        fun provideVoiceLiveClient(logger: AppLogger): LiveClient =
            GeminiLiveClient(logger)

        // ─── AudioEngine: Voice (дефолтный, без квалификатора) ───
        @Provides
        @Singleton
        fun provideVoiceAudioEngine(logger: AppLogger): AudioEngine =
            AndroidAudioEngine(logger)
    }
}