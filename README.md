# Gemini Live Assistant (CopyM)

Голосовой ассистент на базе **Gemini 3.1 Flash Live** с 3D-аватаром,
real-time аудио (16 kHz capture / 24 kHz playback), виземами ARKit-51,
function calling и полным контролем сессии.

## 🎯 Возможности

- 🎙️ **Голос в реальном времени** — WebSocket Bidi Gemini Live v1beta
- 🧠 **Thinking levels** — UltraLow / Low / Balanced / Reasoning
- 🤖 **3D аватар** — Filament/SceneView, ARKit 51 blendshapes, виземы,
  co-articulation, HeadMotionEngine, FacePhysicsEngine
- 🎚️ **Audio pipeline** — AEC, jitter buffer, speaker routing
  (Android 12+ communication device API), программный gain
- 🔧 **Function calling** — 10 демо-функций + get_current_time / get_device_status
- 🔍 **Google Search grounding**
- 💾 **Session resumption + context compression** — переживает потерю сети
- 🔐 **Шифрованный DataStore** (AES-256-GCM, Android Keystore, StrongBox → TEE)
- 📚 **Room-история** диалогов с полнотекстовым поиском
- 🎨 **3 режима сцены** — AVATAR / VISUALIZER / CUSTOM_IMAGE
- 📳 **Haptic feedback** синхронизированный с аудио

## 📱 Требования

| Компонент   | Версия                                                    |
| ----------- | --------------------------------------------------------- |
| Android     | 8.0+ (minSdk 26) — оптимально 12+ для коммуникац. устройств |
| Gemini API  | ключ с доступом к `gemini-3.1-flash-live-preview`        |
| Разрешения  | RECORD_AUDIO, POST_NOTIFICATIONS, INTERNET               |

## 🏗️ Технологический стек

```yaml
Language:    Kotlin 2.1
Build:       Gradle 8.11.1, AGP 8.7.3
JVM:         17
compileSdk:  36
minSdk:      26

Architecture: MVI (Intent/State/Effect) + Clean Layers
UI:           Jetpack Compose + Material3 + Navigation Compose
DI:           Hilt 2.59
Async:        Coroutines 1.10 + Flow
Storage:      Room 2.7 + DataStore 1.2 (encrypted)
Network:      OkHttp 5.3 WebSocket
3D:           SceneView 3.5 (Filament)
Serialization: kotlinx-serialization 1.10
Logging:      Timber 5
```

## 🗂️ Структура проекта

```
app/src/main/java/com/codeextractor/app/
├── MainActivity.kt
├── GeminiLiveApplication.kt
├── GeminiLiveForegroundService.kt
├── data/
│   ├── AndroidAudioEngine.kt          # AudioRecord/AudioTrack + AEC + jitter
│   ├── GeminiLiveClient.kt            # WebSocket + полный setup Gemini 3.1
│   ├── NetworkMonitor.kt
│   ├── PersistentConversationRepository.kt
│   ├── BackgroundImageStore.kt
│   ├── db/                            # Room
│   ├── security/CryptoManager.kt      # AES-GCM
│   └── settings/                      # DataStore + шифрование + миграции
├── di/                                # Hilt модули
├── domain/
│   ├── AudioEngine.kt / LiveClient.kt / ConversationRepository.kt
│   ├── avatar/                        # ARKit / AvatarAnimator / FacePhysics / HeadMotion
│   │   ├── audio/                     # AudioDSPAnalyzer, ProsodyTracker
│   │   ├── linguistics/               # PhoneticRibbon, TextPhonemeAnalyzer, TextAudioPacer
│   │   └── physics/
│   ├── functions/                     # FunctionsRegistry, EventBus (10 тестовых функций)
│   ├── model/                         # ConversationMessage, GeminiEvent, SessionConfig
│   ├── scene/                         # SceneMode
│   └── tools/ToolRegistry.kt          # dispatch function calls
├── editor/GlbEditor.kt                # Редактор GLB-моделей
├── presentation/
│   ├── avatar/                        # AvatarScene, AudioVisualizerScene
│   ├── editor/ModelEditorScreen.kt
│   ├── functions/                     # Экран теста функций
│   ├── navigation/NavGraph.kt
│   ├── settings/                      # SettingsScreen + ViewModel
│   ├── theme/                         # Color, Theme (светлая/тёмная/авто)
│   └── voice/                         # VoiceScreen + VoiceViewModel + haptics
└── util/                              # AppLogger, UiText
```

## 🚀 Быстрый старт

### 1. Получить Gemini API ключ

Перейди на https://aistudio.google.com/, создай ключ.
В приложении введи его в поле "API key" на VoiceScreen.

### 2. Локальная сборка

```bash
./gradlew assembleDebug
# APK в app/build/outputs/apk/debug/
```

### 3. GitHub Actions

При push в `main` собираются Debug и Release APK, загружаются в Artifacts.

## 🔐 Безопасность

- Настройки шифруются AES-256-GCM через Android Keystore.
- StrongBox (Pixel 3+, Samsung S-series) → fallback на TEE (ARM TrustZone).
- Ключ **никогда не покидает Secure Enclave**.
- API-ключ хранится только в локальном DataStore, не передаётся никуда кроме Google.

## 🎤 Audio формат

| Направление | Hz     | Каналы | Формат       |
| ----------- | ------ | ------ | ------------ |
| Capture     | 16 000 | Mono   | PCM 16-bit LE |
| Playback    | 24 000 | Mono   | PCM 16-bit LE |

## ⚙️ Настройки

- **Generation**: temperature, topP, topK, maxOutputTokens, presence/frequency penalty
- **Voice**: 8 Gemini-голосов (Aoede, Puck, Charon, ...)
- **Language**: авто / принудительный код языка (ru, en, ...)
- **Audio**: громкость, усиление микрофона, AEC, принудительный спикер, jitter buffer
- **Session**: resumption, transparent reconnect, context compression
- **VAD**: серверный / клиентский
- **Transcription**: включать для input/output
- **Tools**: Google Search, enableTestFunctions
- **Theme**: AUTO / LIGHT / DARK
- **Scene**: AVATAR / VISUALIZER / CUSTOM_IMAGE
- **Chat**: font scale, timestamps, role labels, auto-scroll, bg alpha
- **Debug**: raw WS frames, usage metadata, debug log

## 📋 Лицензия

Личное использование.