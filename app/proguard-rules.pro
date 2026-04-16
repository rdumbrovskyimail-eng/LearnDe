# ═══════════════════════════════════════════════════════════
#  Base
# ═══════════════════════════════════════════════════════════
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontobfuscate

# ═══════════════════════════════════════════════════════════
#  Kotlin Serialization
# ═══════════════════════════════════════════════════════════
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.codeextractor.app.data.settings.**$$serializer { *; }
-keepclassmembers class com.codeextractor.app.data.settings.** { *** Companion; }
-keepclasseswithmembers class com.codeextractor.app.data.settings.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class *
-keepclassmembers class * {
    @kotlinx.serialization.SerialName *;
}

# ═══════════════════════════════════════════════════════════
#  Kotlin reflection / metadata
# ═══════════════════════════════════════════════════════════
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# ═══════════════════════════════════════════════════════════
#  Coroutines
# ═══════════════════════════════════════════════════════════
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ═══════════════════════════════════════════════════════════
#  Android Keystore
# ═══════════════════════════════════════════════════════════
-keep class android.security.keystore.** { *; }

# ═══════════════════════════════════════════════════════════
#  OkHttp 5.x / Okio
# ═══════════════════════════════════════════════════════════
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ═══════════════════════════════════════════════════════════
#  Hilt / Dagger
# ═══════════════════════════════════════════════════════════
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { <init>(...); }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# ═══════════════════════════════════════════════════════════
#  Room
# ═══════════════════════════════════════════════════════════
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# ═══════════════════════════════════════════════════════════
#  Jetpack Compose
# ═══════════════════════════════════════════════════════════
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keepclassmembers class androidx.compose.** {
    <init>(...);
}

# ═══════════════════════════════════════════════════════════
#  SceneView / Filament / ARCore
# ═══════════════════════════════════════════════════════════
-keep class com.google.android.filament.** { *; }
-keep class com.google.android.filament.utils.** { *; }
-keep class io.github.sceneview.** { *; }
-keep class com.google.ar.** { *; }
-dontwarn com.google.ar.core.**
-dontwarn com.google.ar.sceneform.**
-dontwarn com.google.android.filament.**

# ═══════════════════════════════════════════════════════════
#  Timber
# ═══════════════════════════════════════════════════════════
-dontwarn org.jetbrains.annotations.**