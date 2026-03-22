package com.codeextractor.app.audio

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class AudioDispatcherProvider {

    val recorder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GeminiAudioRecorder").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()

    val player = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GeminiAudioPlayer").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()

    fun shutdown() {
        recorder.close()
        player.close()
    }
}