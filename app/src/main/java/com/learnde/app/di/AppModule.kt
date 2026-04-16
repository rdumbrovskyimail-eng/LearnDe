package com.learnde.app.di

import com.learnde.app.data.AndroidAudioEngine
import com.learnde.app.data.GeminiLiveClient
import com.learnde.app.data.PersistentConversationRepository
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.ConversationRepository
import com.learnde.app.domain.LiveClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль: привязка интерфейсов к реализациям.
 * Singleton scope — один экземпляр на всё приложение.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindLiveClient(impl: GeminiLiveClient): LiveClient

    @Binds
    @Singleton
    abstract fun bindAudioEngine(impl: AndroidAudioEngine): AudioEngine

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: PersistentConversationRepository): ConversationRepository

    // BackgroundImageStore и FunctionsEventBus используют @Singleton + @Inject constructor,
    // поэтому Hilt сам их регистрирует без @Provides. Дополнительная конфигурация не нужна.
}
