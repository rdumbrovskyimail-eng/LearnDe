// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/core/TranslatorTextScope.kt
//
// Qualifier для второго LiveClient-инстанса, специализированного
// под транскрипцию пользователя в text-mode для translator-сессии.
// Изолирован от @LearnScope (audio) и @VoiceScope.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TranslatorTextScope