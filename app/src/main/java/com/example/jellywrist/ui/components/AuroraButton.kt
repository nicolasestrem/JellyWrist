package com.example.jellywrist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import com.example.jellywrist.ui.theme.GradientEnd
import com.example.jellywrist.ui.theme.GradientStart
import com.example.jellywrist.ui.theme.auroraGradient

/**
 * Round button with the signature gradient (violet → magenta) — reserved for the primary action.
 *
 * Material3 `ButtonColors` only handles a solid color, so we draw the gradient
 * as the background of a clipped [Box]. The pressed indication is a darkening of
 * the gradient (no external ripple dependency — stays lean and reliable under
 * `warningsAsErrors = true`). The caller provides the size (≥ 48 dp touch target)
 * and the content (icon or text).
 */
@Composable
fun AuroraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        !enabled -> Brush.linearGradient(
            listOf(GradientStart.copy(alpha = 0.38f), GradientEnd.copy(alpha = 0.38f)),
        )
        pressed -> Brush.linearGradient(
            listOf(GradientStart.copy(alpha = 0.75f), GradientEnd.copy(alpha = 0.75f)),
        )
        else -> auroraGradient()
    }
    Box(
        modifier
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}
