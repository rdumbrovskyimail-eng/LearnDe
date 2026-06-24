package com.learnde.app.presentation.client

import androidx.lifecycle.ViewModel
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.ConnectionOrchestrator
import com.learnde.app.domain.LiveClient
import androidx.datastore.core.DataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ClientViewModel @Inject constructor(
    private val liveClient: LiveClient,
    private val audioEngine: AudioEngine,
    private val settingsStore: DataStore<AppSettings>,
    private val orchestrator: ConnectionOrchestrator
) : ViewModel() {
    // TODO: Implement logic
}