package com.example.jellywrist.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.example.jellywrist.ui.theme.auroraGradient

/**
 * Text painted with the signature gradient (violet → magenta).
 *
 * Used for the Quick Connect code (large, legible, recognizable at a glance)
 * and brand accents. The default [style] is `displayMedium`.
 */
@Composable
fun GradientText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayMedium,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(brush = auroraGradient()),
        textAlign = textAlign,
    )
}
