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
abstract class RepositoryBindingModule {
    @Binds
    @Singleton
    abstract fun bindConversationRepository(
        impl: PersistentConversationRepository
    ): ConversationRepository
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideLiveClient(logger: AppLogger): LiveClient = GeminiLiveClient(logger)

    @Provides
    @Singleton
    fun provideAudioEngine(logger: AppLogger): AudioEngine = AndroidAudioEngine(logger)
}