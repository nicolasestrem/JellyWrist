# JellyWrist R8 rules (PLAN.md §8, task 14).
#
# Principle: keep only what R8 cannot prove reachable. Hilt/Dagger, Room
# (*_Impl classes) and Media3 ship their own consumer rules in their AARs —
# do NOT duplicate them here, they would have to be maintained twice.

# ── Jellyfin SDK: generated @Serializable DTOs ───────────────────────────────────────
# kotlinx-serialization resolves serializers by reflection on the synthetic
# `Companion.serializer()` and `$$serializer` members of the generated classes.
# R8 prunes them (no static reference) and the SDK then fails on the first call
# with "Serializer for class … is not found". The models are ~600 classes;
# keeping them is the price of end-to-end typing (REQ-002).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class org.jellyfin.sdk.model.**$$serializer { *; }
-keepclassmembers class org.jellyfin.sdk.model.** {
    *** Companion;
}
-keepclasseswithmembers class org.jellyfin.sdk.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── slf4j-nop ────────────────────────────────────────────────────────────────────────
# The SDK logs through kotlin-logging→slf4j; without a runtime backend it raises
# NoClassDefFoundError while loading the class (see docs/claude-code-reference.md).
# The nop binder is resolved reflectively: R8 does not see the reference.
-keep class org.slf4j.helpers.** { *; }
-dontwarn org.slf4j.impl.StaticLoggerBinder

# ── Coroutines: debug diagnostics are pruned cleanly ─────────────────────────────────
-dontwarn kotlinx.coroutines.debug.**

# ── Wire @Serializable DTOs (companion protocol, :core) ──────────────────────────────
# Wire-protocol DTOs dexed from :core undergo the same R8 pruning as the SDK
# models: the synthetic `$$serializer` members have no static reference and
# would disappear without this rule.
-keep,includedescriptorclasses class com.example.jellywrist.wire.**$$serializer { *; }
-keepclassmembers class com.example.jellywrist.wire.** { *** Companion; }
-keepclasseswithmembers class com.example.jellywrist.wire.** { kotlinx.serialization.KSerializer serializer(...); }
