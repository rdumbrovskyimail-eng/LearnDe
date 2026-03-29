package com.codeextractor.app.di

import com.codeextractor.app.data.AndroidAudioEngine
import com.codeextractor.app.data.GeminiLiveClient
import com.codeextractor.app.domain.AudioEngine
import com.codeextractor.app.domain.LiveClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль: привязка интерфейсов к реализациям.
 * Singleton scope — один экземпляр WebSocket и AudioEngine на всё приложение.
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
}