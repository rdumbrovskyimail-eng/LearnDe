package com.codeextractor.app.domain.scene

/**
 * Режим отображения «сцены» под голосовым ассистентом.
 *
 * AVATAR      — 3D-модель лица (по умолчанию, как сейчас).
 * VISUALIZER  — чёрный экран с пульсирующей радужной анимацией,
 *               реагирующей на звук модели. Использует playbackSync
 *               и AvatarAnimator.prosody для получения уровня звука.
 * CUSTOM_IMAGE — пользовательское фоновое изображение (PNG из галереи).
 */
enum class SceneMode(val id: String, val displayName: String) {
    AVATAR      ("avatar",      "3D-аватар"),
    VISUALIZER  ("visualizer",  "Радужный визуализатор"),
    CUSTOM_IMAGE("custom_image","Своя картинка фоном");

    companion object {
        fun from(id: String): SceneMode =
            entries.firstOrNull { it.id == id } ?: AVATAR
    }
}