# 🚀 Code Extractor - Быстрый старт

## ✨ Что это?

Мощное Android-приложение на **Kotlin 2.1** для извлечения всего кода из Android-проектов в один TXT файл.

## 🎯 Основные фичи

✅ Работает прямо на Android-телефоне  
✅ Извлекает код из **ВСЕХ** файлов проекта (кроме JAR)  
✅ Добавляйте целые папки или отдельные файлы  
✅ Все коды в 1 красиво отформатированный TXT  
✅ Автоматическая компиляция через GitHub Actions  
✅ Минималистичная структура проекта  

## 📱 Как пользоваться приложением

1. **Добавить папку** → выберите папку с файлами проекта
2. **Добавить файлы** → добавьте отдельные файлы (опционально)
3. **ОБРАБОТАТЬ** → выберите куда сохранить TXT файл
4. Готово! 🎉

## 🛠️ Установка проекта

### Вариант 1: Через GitHub (РЕКОМЕНДУЕТСЯ)

```bash
# 1. Скачайте gradle-wrapper.jar
# Перейдите в gradle/wrapper/ и выполните:
curl -o gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar

# 2. Создайте репозиторий на GitHub
# 3. Загрузите проект:
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/CodeExtractor.git
git push -u origin main

# 4. GitHub Actions автоматически соберет APK!
# Скачайте из: Actions → Artifacts или Releases
```

### Вариант 2: Локальная сборка

```bash
# 1. Установите Android Studio
# 2. Откройте проект в Android Studio
# 3. Android Studio автоматически настроит Gradle
# 4. Соберите: Build → Build Bundle(s) / APK(s) → Build APK(s)
```

### Вариант 3: Через командную строку

```bash
# 1. Убедитесь что gradle-wrapper.jar существует
# 2. Выполните:
chmod +x gradlew
./gradlew assembleDebug

# APK будет в: app/build/outputs/apk/debug/
```

## 📦 Поддерживаемые форматы файлов

| Категория | Расширения |
|-----------|-----------|
| Kotlin/Java | kt, kts, java |
| XML | xml |
| Gradle | gradle, properties |
| Android | aidl, rs |
| Web | html, css, js, json |
| Config | yml, yaml, toml, ini, conf |
| Text | txt, md |
| C/C++ | c, cpp, h, hpp |
| Python | py |
| Scripts | sh, bat, cmake, pro |

**ВАЖНО**: JAR файлы исключены из обработки!

## 🏗️ Структура проекта

```
CodeExtractorApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/codeextractor/app/
│   │   │   ├── MainActivity.kt          # UI и логика
│   │   │   └── CodeProcessor.kt         # Обработка файлов
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml    # Интерфейс
│   │   │   └── values/                  # Ресурсы
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── .github/workflows/
│   └── android-build.yml                # CI/CD
├── gradle/wrapper/
│   └── gradle-wrapper.jar               # ⚠️ Скачайте!
├── build.gradle.kts
└── settings.gradle.kts
```

## 🔧 Технологии

- **Kotlin**: 2.1.0
- **Android SDK**: 35 (compileSdk)
- **Min SDK**: 26 (Android 8.0+)
- **Target SDK**: 35
- **Gradle**: 8.11.1
- **AGP**: 8.7.3
- **Libraries**: Material3, Coroutines, ViewBinding

## 📝 GitHub Actions

Workflow автоматически:
- ✅ Собирает Debug APK
- ✅ Собирает Release APK  
- ✅ Создает Artifacts для скачивания
- ✅ Публикует Releases с APK

## 🎨 UI/UX

- **Material Design 3** для современного вида
- **2 кнопки сверху**: Добавить папку / Добавить файлы
- **Счетчик** выбранных файлов
- **Кнопка "ОБРАБОТАТЬ"** - создает финальный TXT
- **Прогресс-бар** во время обработки
- **Кнопка "Очистить"** для сброса списка

## 🔐 Разрешения

Приложение запрашивает:
- `READ_EXTERNAL_STORAGE` (Android 12-)
- `WRITE_EXTERNAL_STORAGE` (Android 12-)
- `MANAGE_EXTERNAL_STORAGE` (Android 11+)

## 📱 Требования к устройству

- **Android**: 8.0+ (API 26+)
- **Устройство**: Любое (протестировано на S23 Ultra)
- **Android версия**: Поддерживает Android 16

## 🐛 Решение проблем

### Gradle не работает
```bash
# Скачайте gradle-wrapper.jar:
cd gradle/wrapper/
curl -O https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar
```

### Приложение не устанавливается
1. Настройки → Безопасность → Неизвестные источники → ВКЛ
2. Используйте APK из официального билда

### GitHub Actions падает
- Убедитесь что `gradle-wrapper.jar` есть в репозитории
- Проверьте что все файлы закоммичены

## 💡 Пример использования

1. Открыли приложение
2. Нажали "Добавить папку" → выбрали `/sdcard/Projects/MyApp/app/src/main/java/`
3. Нажали "Добавить файлы" → выбрали `AndroidManifest.xml`
4. Видим: "Выбрано файлов: 25"
5. Нажали "ОБРАБОТАТЬ"
6. Выбрали `/sdcard/Download/extracted_code.txt`
7. Готово! Весь код в одном файле 🎉

## 📄 Формат выходного файла

```
================================================================================
ФАЙЛ: MainActivity.kt
================================================================================

package com.example.app
...код файла...


================================================================================
ФАЙЛ: build.gradle.kts
================================================================================

plugins {
...код файла...
```

## 🚀 Быстрый тест

```bash
# 1. Скачайте проект
# 2. Добавьте gradle-wrapper.jar
# 3. Выполните:
./gradlew assembleDebug

# Если сборка успешна - проект готов к загрузке на GitHub!
```

## 📧 Поддержка

Вопросы? Создайте Issue на GitHub!

---

**Сделано с ❤️ для моддинга Android-проектов на Kotlin 2.1**
