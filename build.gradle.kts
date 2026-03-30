plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.28" apply false
    id("com.google.dagger.hilt.android") version "2.57.1" apply false

    id("com.github.ben-manes.versions") version "0.51.0"
}

tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
    rejectVersionIf {
        val isStable = "^[0-9,.v-]+(-r)?$".toRegex().matches(candidate.version)
        val isUnstable = candidate.version.matches(".*(alpha|beta|rc|RC|m|M).*".toRegex())
        !isStable && isUnstable
    }
}