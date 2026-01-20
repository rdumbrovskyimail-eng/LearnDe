# Инструкция по настройке проекта

## Шаг 1: Добавление gradle-wrapper.jar

Gradle Wrapper JAR файл необходим для работы проекта. Выполните одну из следующих команд:

### Вариант А: Используя существующий Gradle (если установлен)
```bash
gradle wrapper --gradle-version 8.11.1
```

### Вариант Б: Скачать вручную
1. Скачайте файл с: https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar
2. Поместите его в `gradle/wrapper/gradle-wrapper.jar`

### Вариант В: Использовать Android Studio
1. Откройте проект в Android Studio
2. Android Studio автоматически создаст gradle-wrapper.jar

## Шаг 2: Создание иконок приложения

Создайте PNG иконки для приложения или используйте Android Studio:
1. Откройте Android Studio
2. Правой кнопкой на `res` → New → Image Asset
3. Настройте иконку и сгенерируйте для всех разрешений

Или используйте онлайн генератор: https://romannurik.github.io/AndroidAssetStudio/

## Шаг 3: Загрузка на GitHub

```bash
# Инициализируйте git репозиторий
git init

# Добавьте все файлы
git add .

# Создайте первый коммит
git commit -m "Initial commit: Code Extractor App"

# Добавьте remote репозиторий
git remote add origin https://github.com/ВАШ_USERNAME/CodeExtractor.git

# Отправьте на GitHub
git branch -M main
git push -u origin main
```

## Шаг 4: GitHub Actions

После push на GitHub, Actions автоматически:
- Соберет Debug APK
- Соберет Release APK
- Создаст Release с APK файлами

APK файлы будут доступны в:
- **Actions** → последний workflow → **Artifacts**
- **Releases** (если был push в main)

## Шаг 5: Установка на Android

1. Скачайте APK из GitHub Releases или Artifacts
2. Перенесите на телефон
3. Разрешите установку из неизвестных источников
4. Установите APK
5. При первом запуске разрешите доступ к файлам

## Структура файлов проекта

```
CodeExtractorApp/
├── .github/
│   └── workflows/
│       └── android-build.yml       # CI/CD конфигурация
├── app/
│   ├── src/main/
│   │   ├── java/com/codeextractor/app/
│   │   │   ├── MainActivity.kt
│   │   │   └── CodeProcessor.kt
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/wrapper/
│   ├── gradle-wrapper.jar          # ⚠️ Добавьте этот файл!
│   └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew                          # Unix/Mac
├── gradlew.bat                      # Windows
├── .gitignore
└── README.md
```

## Локальная сборка (опционально)

Если хотите собрать локально на своем компьютере:

```bash
# Debug версия
./gradlew assembleDebug

# Release версия
./gradlew assembleRelease

# APK будет в: app/build/outputs/apk/
```

## Решение проблем

### Gradle не запускается
- Проверьте, что `gradle-wrapper.jar` существует
- Убедитесь, что `gradlew` имеет права на выполнение: `chmod +x gradlew`

### Ошибки сборки в GitHub Actions
- Проверьте, что все файлы добавлены в git
- Убедитесь, что `gradle-wrapper.jar` закоммичен

### Приложение не устанавливается
- Включите "Установка из неизвестных источников" в настройках Android
- Проверьте, что скачали правильный APK (для вашей архитектуры)

## Поддержка

Для вопросов и проблем создайте Issue на GitHub.
