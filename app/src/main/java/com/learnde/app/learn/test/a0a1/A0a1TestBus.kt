// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА + ПЕРЕИМЕНОВАНИЕ ПАКЕТА
// Старый путь: app/src/main/java/com/learnde/app/Learn/Test/A0a1/A0a1TestBus.kt
// Новый путь:  app/src/main/java/com/learnde/app/learn/test/a0a1/A0a1TestBus.kt
//
// Изменения:
//   • Пакет lowercase (Java convention, стабильность Hilt/KSP).
//   • Убраны startSignal/exitSignal — переехали в LearnSessionController.
//   • Добавлен dedup по callId (tryConsume).
//   • Осталось ТОЛЬКО то, что нужно UI: awards + finished.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.test.a0a1

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class A0a1TestBus @Inject constructor() {

    // ───── Оценки: балл и текстовый фидбек от ИИ ─────
    private val _awards = MutableSharedFlow<Pair<Int, String>>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val awards: SharedFlow<Pair<Int, String>> = _awards.asSharedFlow()

    // ───── Финал ─────
    private val _finished = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    // ───── Dedup по callId ─────
    private val processedIds = ConcurrentHashMap.newKeySet<String>()
    private val maxProcessed = 256

    /**
     * Попытаться "потребить" callId. Возвращает true если id новый,
     * false если мы уже его обрабатывали (дубликат после reconnect).
     *
     * Пустой id (не задан сервером) — всегда true (dedup невозможен).
     */
    fun tryConsume(id: String): Boolean {
        if (id.isBlank()) return true
        val added = processedIds.add(id)
        // Простой FIFO-like cap: при переполнении удаляем один старый
        if (processedIds.size > maxProcessed) {
            val iter = processedIds.iterator()
            if (iter.hasNext()) {
                iter.next()
                iter.remove()
            }
        }
        return added
    }

    /** Сбросить dedup-кэш (вызывается в onEnter сессии и в restart UI). */
    fun reset() {
        processedIds.clear()
    }

    fun publishAward(points: Int, feedback: String) { _awards.tryEmit(points to feedback) }
    fun publishFinish()            { _finished.tryEmit(Unit) }
}
