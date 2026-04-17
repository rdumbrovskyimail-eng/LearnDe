package com.learnde.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.data.settings.AppSettingsSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideAppSettingsDataStore(
        @ApplicationContext context: Context,
        serializer: AppSettingsSerializer
    ): DataStore<AppSettings> =
        DataStoreFactory.create(
            serializer = serializer,
            produceFile = { context.dataStoreFile("app_settings_encrypted.json") },
            corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
}