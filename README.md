# Code Extractor

Мощное Android-приложение для извлечения кода из файлов проекта.

## Возможности

- ✅ Извлечение кода из всех типов файлов Android-проекта (кроме JAR)
- ✅ Поддержка добавления целых папок и отдельных файлов
- ✅ Рекурсивный обход вложенных директорий
- ✅ Объединение всех кодов в один TXT файл
- ✅ Красивое форматирование с разделителями

## Поддерживаемые форматы

**Kotlin/Java**: kt, kts, java  
**XML**: xml  
**Gradle**: gradle, properties  
**Android**: aidl, rs  
**Web**: html, css, js, json  
**Config**: yml, yaml, toml, ini, conf, config  
**Text**: txt, md  
**C/C++**: c, cpp, h, hpp  
**Python**: py  
**Other**: sh, bat, cmake, pro

## Использование

1. Нажмите **"Добавить папку"** - выберите папку с файлами
2. Нажмите **"Добавить файлы"** - выберите отдельные файлы (необязательно)
3. Нажмите **"ОБРАБОТАТЬ"** - выберите место сохранения TXT файла
4. Готово! Все коды собраны в один файл

## Технологии

- Kotlin 2.1
- Android SDK 35
- Material Design 3
- Coroutines для асинхронной работы
- ViewBinding
- Storage Access Framework

## Сборка

### Локальная сборка

```bash
./gradlew assembleDebug
```

### GitHub Actions

Приложение автоматически собирается при push в main/master ветку.  
APK файлы доступны в разделе **Actions** → **Artifacts**

## Требования

- Android 8.0 (API 26) и выше
- Разрешения на доступ к файлам

## Структура проекта

```
CodeExtractorApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/codeextractor/app/
│   │   │   ├── MainActivity.kt          # Главное Activity
│   │   │   └── CodeProcessor.kt         # Логика обработки файлов
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml    # UI макет
│   │   │   ├── values/                  # Ресурсы
│   │   │   └── xml/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── .github/workflows/
│   └── android-build.yml                # CI/CD конфигурация
├── build.gradle.kts
└── settings.gradle.kts
```

## Разрешения

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

## Лицензия

Свободное использование для личных проектов

## Автор

Создано для моддинга Android-проектов 
