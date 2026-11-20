# ── DTOs Jellyfin @Serializable ─────────────────────────────────────────────
-keep,includedescriptorclasses class org.jellyfin.sdk.model.**$$serializer { *; }
-keepclassmembers class org.jellyfin.sdk.model.** { *** Companion; }
-keepclasseswithmembers class org.jellyfin.sdk.model.** { kotlinx.serialization.KSerializer serializer(...); }

# ── DTOs wire @Serializable (protocole compagnon) ──────────────────────────
-keep,includedescriptorclasses class com.example.jellywrist.wire.**$$serializer { *; }
-keepclassmembers class com.example.jellywrist.wire.** { *** Companion; }
-keepclasseswithmembers class com.example.jellywrist.wire.** { kotlinx.serialization.KSerializer serializer(...); }

# ── SLF4J fourni par le SDK Jellyfin ─────────────────────────────────────────
-keep class org.slf4j.helpers.** { *; }
-dontwarn org.slf4j.impl.StaticLoggerBinder
