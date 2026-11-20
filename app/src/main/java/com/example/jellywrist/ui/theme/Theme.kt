package com.example.jellywrist.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * "Aurora" color scheme derived from the default Wear Material3 scheme.
 *
 * Wear 1.6.2 doesn't expose a `darkColorScheme()` factory: we start from the
 * current scheme (dark by default on the watch) and override the roles the UI
 * actually consumes. `copy` keeps the other values consistent.
 */
@Composable
fun jellyWristColorScheme(): ColorScheme {
    // remember(base): a fresh ColorScheme instance on every recomposition invalidated
    // every LocalColorScheme reader in the tree.
    val base = MaterialTheme.colorScheme
    return remember(base) { base.toAuroraScheme() }
}

private fun ColorScheme.toAuroraScheme(): ColorScheme = copy(
    primary = AuroraPrimary,
    primaryContainer = AuroraPrimary,
    onPrimary = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = AuroraSecondary,
    secondaryContainer = AuroraSecondary,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = AuroraTertiary,
    onTertiary = Color(0xFF001016),
    surfaceContainerLow = AuroraBlack,
    surfaceContainer = AuroraSurface,
    surfaceContainerHigh = AuroraSurfaceVariant,
    onSurface = AuroraOnBackground,
    onSurfaceVariant = AuroraOnSurfaceVariant,
    outline = AuroraOutline,
    outlineVariant = AuroraSurfaceVariant,
    background = AuroraBlack,
    onBackground = AuroraOnBackground,
    error = AuroraError,
    errorContainer = AuroraError,
    onError = Color(0xFF000000),
)

/**
 * The JellyWrist "Aurora" theme.
 *
 * Single injection point: replaces the bare `MaterialTheme {}` previously used
 * in `JellyNav`. Centralizes palette + typography so every screen shares the
 * same identity.
 */
@Composable
fun JellyWristTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = jellyWristColorScheme(),
        typography = JellyWristTypography,
        content = content,
    )
}

// --- Reusable gradient brushes ----------------------------------------------
//
// Singleton Brush instances: a Brush is immutable and sizes itself at draw time, so
// there is no reason to allocate a new one per call — these functions used to be
// called from DrawScope (two allocations per frame while a seek bar animated) and
// from component recompositions.

private val AuroraGradientBrush: Brush = Brush.linearGradient(listOf(GradientStart, GradientEnd))

private val AuroraScrimBottomBrush: Brush = Brush.verticalGradient(
    0.00f to Color.Transparent,
    0.45f to Color(0xAA000000),
    1.00f to Color(0xF2000000),
)

private val AuroraScrimCenterBrush: Brush = Brush.radialGradient(
    0.0f to Color(0x33000000),
    1.0f to Color(0xE6000000),
)

/**
 * Signature violet → magenta gradient, diagonal top-left → bottom-right.
 * Used for the Play button, the player progress bar, gauges, the active tab
 * indicator, and the Quick Connect code.
 */
fun auroraGradient(): Brush = AuroraGradientBrush

/**
 * Dark vertical scrim to improve legibility of text placed over full-frame
 * artwork (transparent at the top → nearly opaque at the bottom).
 */
fun auroraScrimBottom(): Brush = AuroraScrimBottomBrush

/**
 * Soft centered radial scrim, to fade artwork used as a screen background while
 * keeping a halo of color at the center.
 */
fun auroraScrimCenter(): Brush = AuroraScrimCenterBrush
