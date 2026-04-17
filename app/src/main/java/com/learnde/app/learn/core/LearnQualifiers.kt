// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnQualifiers.kt
//
// Hilt-квалификаторы для разделения Voice- и Learn-инстансов
// LiveClient и AudioEngine.
//
// VoiceScope       — используется в VoiceViewModel (имплицитно — он
//                    инжектит без квалификатора, и старый @Singleton binding
//                    по дефолту считается VoiceScope).
// LearnScope       — для автономного учебного стека.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LearnScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceScope