package com.codeextractor.app.audio

import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

object AudioEffectsHelper {
    private const val TAG = "AudioEffects"

    fun enable(sessionId: Int): Pair<NoiseSuppressor?, AutomaticGainControl?> {
        val ns = enableNoiseSuppressor(sessionId)
        val agc = enableAutomaticGainControl(sessionId)
        return Pair(ns, agc)
    }

    private fun enableNoiseSuppressor(sessionId: Int): NoiseSuppressor? {
        if (!NoiseSuppressor.isAvailable()) {
            Log.d(TAG, "NoiseSuppressor not available on this device")
            return null
        }
        return runCatching {
            NoiseSuppressor.create(sessionId)?.apply {
                enabled = true
                Log.d(TAG, "NoiseSuppressor enabled (session=$sessionId)")
            }
        }.onFailure {
            Log.w(TAG, "NoiseSuppressor init error: ${it.message}")
        }.getOrNull()
    }

    private fun enableAutomaticGainControl(sessionId: Int): AutomaticGainControl? {
        if (!AutomaticGainControl.isAvailable()) {
            Log.d(TAG, "AutomaticGainControl not available on this device")
            return null
        }
        return runCatching {
            AutomaticGainControl.create(sessionId)?.apply {
                enabled = true
                Log.d(TAG, "AutomaticGainControl enabled (session=$sessionId)")
            }
        }.onFailure {
            Log.w(TAG, "AutomaticGainControl init error: ${it.message}")
        }.getOrNull()
    }

    fun release(
        noiseSuppressor: NoiseSuppressor?,
        gainControl: AutomaticGainControl?
    ) {
        noiseSuppressor?.let { runCatching { it.enabled = false; it.release() } }
        gainControl?.let { runCatching { it.enabled = false; it.release() } }
    }
}