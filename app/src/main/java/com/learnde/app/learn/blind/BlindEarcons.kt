package com.learnde.app.learn.blind

import android.media.AudioManager
import android.media.ToneGenerator
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlindEarcons @Inject constructor(
    private val logger: AppLogger,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    @Volatile
    private var generator: ToneGenerator? = null

    /** Громкость 0..100 относительно STREAM_MUSIC. */
    private val volume = 80

    // ─────────────────────────────────────────────────────────────
    //  Публичные сигналы
    // ─────────────────────────────────────────────────────────────

    /** Урок завершён — двойной восходящий тон. */
    fun lessonFinished() = play {
        tone(ToneGenerator.TONE_PROP_BEEP, 120)
        delay(140)
        tone(ToneGenerator.TONE_PROP_ACK, 220)
    }

    /** Старт следующего урока — одиночный короткий тон. */
    fun lessonStarting() = play {
        tone(ToneGenerator.TONE_PROP_BEEP2, 160)
    }

    /** Цепочка завершена или А1 полностью пройден — «фанфары». */
    fun chainCompleted() = play {
        tone(ToneGenerator.TONE_DTMF_1, 130)
        delay(150)
        tone(ToneGenerator.TONE_DTMF_5, 130)
        delay(150)
        tone(ToneGenerator.TONE_DTMF_9, 320)
    }

    /** Пауза — низкий тон. */
    fun paused() = play {
        tone(ToneGenerator.TONE_DTMF_0, 240)
    }

    /** Продолжение — средний тон. */
    fun resumed() = play {
        tone(ToneGenerator.TONE_DTMF_5, 160)
    }

    /** Ошибка/перезапуск — два низких тона. */
    fun error() = play {
        tone(ToneGenerator.TONE_DTMF_0, 140)
        delay(160)
        tone(ToneGenerator.TONE_DTMF_0, 140)
    }

    /** Освободить ресурсы (вызывается при выключении Слепого режима). */
    fun release() {
        scope.launch {
            mutex.withLock {
                runCatching { generator?.release() }
                generator = null
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Внутреннее
    // ─────────────────────────────────────────────────────────────

    private fun play(block: suspend PlayScope.() -> Unit) {
        scope.launch {
            mutex.withLock {
                runCatching { PlayScope().block() }
                    .onFailure { logger.w("BlindEarcons: tone failed: ${it.message}") }
            }
        }
    }

    private inner class PlayScope {
        suspend fun tone(toneType: Int, durationMs: Int) {
            val gen = obtain() ?: return
            val ok = runCatching { gen.startTone(toneType, durationMs) }.getOrDefault(false)
            if (!ok) {
                // Generator мог «протухнуть» после смены аудио-маршрута — пересоздаём один раз.
                recreate()
                runCatching { generator?.startTone(toneType, durationMs) }
            }
            delay(durationMs.toLong())
        }
    }

    private fun obtain(): ToneGenerator? {
        generator?.let { return it }
        return recreate()
    }

    private fun recreate(): ToneGenerator? {
        runCatching { generator?.release() }
        generator = runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, volume)
        }.onFailure {
            logger.w("BlindEarcons: cannot create ToneGenerator: ${it.message}")
        }.getOrNull()
        return generator
    }
}