package com.codeextractor.app.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object WebSocketExtensions {

    fun buildProductionClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun computeBackoffDelay(
        attempt: Int,
        baseMs: Long = 2_000L,
        capMs: Long = 30_000L,
        jitterFactor: Double = 0.2
    ): Long {
        val exponential = (baseMs * (1L shl attempt)).coerceAtMost(capMs)
        val jitter = (exponential * jitterFactor * (Random.nextDouble() * 2.0 - 1.0)).toLong()
        return (exponential + jitter).coerceAtLeast(baseMs / 2)
    }
}