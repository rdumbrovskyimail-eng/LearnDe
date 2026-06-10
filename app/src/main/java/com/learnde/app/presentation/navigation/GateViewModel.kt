package com.learnde.app.presentation.navigation

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import com.learnde.app.data.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class GateViewModel @Inject constructor(
    settingsStore: DataStore<AppSettings>,
) : ViewModel() {
    val testPassed: Flow<Boolean> = settingsStore.data.map { it.testPassed }
}