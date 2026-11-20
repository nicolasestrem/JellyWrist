package com.example.jellywrist.ui.theme

import androidx.compose.ui.graphics.Color

// --- "Aurora" palette -------------------------------------------------------
// OLED-black base (saves energy on AMOLED watches), slightly purple-tinted
// surface, signature violet → magenta accent. The JellyWrist visual identity:
// understated, premium, immersive — designed for a round watch screen.
//
// The Wear Material3 color scheme doesn't expose a `darkColorScheme` factory
// in 1.6.2: we derive it in [JellyWristTheme] instead, by copying the default
// scheme and overriding the roles we need (see Theme.kt).

/** Pure black — saves pixels on AMOLED and maximizes contrast. */
internal val AuroraBlack = Color(0xFF000000)

/** Elevated surface: very slight purple-black to distinguish cards from the background. */
internal val AuroraSurface = Color(0xFF0E0C12)

/** Secondary surface (tonal chips, dividers) — one step lighter. */
internal val AuroraSurfaceVariant = Color(0xFF191620)

/** Violet — primary (main actions, selection). */
internal val AuroraPrimary = Color(0xFF8B5CF6)

/** Magenta — secondary (gradient endpoint, highlights). */
internal val AuroraSecondary = Color(0xFFEC4899)

/** Info cyan — tertiary (informational status badges). */
internal val AuroraTertiary = Color(0xFF22D3EE)

/** Primary text, near-white with a purple tint. */
internal val AuroraOnBackground = Color(0xFFF4F2F7)

/** Secondary text, muted purple-gray. */
internal val AuroraOnSurfaceVariant = Color(0xFFA39FB5)

/** Thin outline (outlined chips). */
internal val AuroraOutline = Color(0xFF322D3D)

/** Error — warm pink (failed login, failed sync, sign-out). */
internal val AuroraError = Color(0xFFFF5C8A)

// --- Signature gradient endpoints -------------------------------------------
internal val GradientStart = AuroraPrimary
internal val GradientEnd = AuroraSecondary
