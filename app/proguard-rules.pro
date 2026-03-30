# ── Базовые ───────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Kotlin Serialization ──────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
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

# ── Android Keystore ──────────────────────────────────────────────────────────
-keep class android.security.keystore.** { *; }

# ── OkHttp 5.x ────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Hilt ─────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }