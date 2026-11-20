package com.example.jellywrist.mobile.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private val AuroraScheme = darkColorScheme(
    primary = AuroraPrimary,
    onPrimary = AuroraBlack,
    secondary = AuroraSecondary,
    onSecondary = AuroraBlack,
    tertiary = AuroraTertiary,
    background = AuroraBlack,
    onBackground = AuroraOnBackground,
    surface = AuroraSurface,
    onSurface = AuroraOnBackground,
    surfaceVariant = AuroraSurfaceVariant,
    onSurfaceVariant = AuroraOnSurfaceVariant,
    outline = AuroraOutline,
    error = AuroraError,
)

@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AuroraScheme) {
        Surface(modifier = Modifier.fillMaxSize(), content = content)
    }
}
