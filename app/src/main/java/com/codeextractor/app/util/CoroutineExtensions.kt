package com.codeextractor.app.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

suspend fun awaitSetupCompleteWithTimeout(
    setupComplete: CompletableDeferred<Unit>,
    timeoutMs: Long = 10_000L
): Result<Unit> = runCatching {
    withTimeout(timeoutMs) {
        setupComplete.await()
    }
}.onFailure { e ->
    when (e) {
        is TimeoutCancellationException ->
            Log.e("GeminiLive", "Setup timeout after ${timeoutMs}ms — no setupComplete received")
        is CancellationException ->
            Log.d("GeminiLive", "Setup awaiting cancelled (session reset)")
        else ->
            Log.e("GeminiLive", "Setup await error: ${e.message}")
    }
}