package com.example.jellywrist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.example.jellywrist.ui.theme.auroraGradient

/** Visual variants of [AuroraChip]. */
enum class AuroraChipVariant {
    /** Signature gradient background (primary action / selection). */
    Filled,

    /** Tonal surface (default list row). */
    Tonal,

    /** Transparent with a thin outline (secondary navigation). */
    Outlined,
}

/**
 * "Chip" list row — the most-used component in the UI.
 *
 * Wear Material3 doesn't provide a `Chip`, so we formalize one: 52 dp height,
 * rounded corners, optional leading icon, primary label plus optional
 * secondary label, and a free `trailing` slot (badge, switch, icon).
 * Three variants ([AuroraChipVariant]).
 *
 * The pressed indication is an alpha change (no external ripple).
 */
@Composable
fun AuroraChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    leadingIcon: ImageVector? = null,
    leadingContent: @Composable (RowScope.() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    /** Optional checked state; when present, exposes this chip as a switch. */
    checked: Boolean? = null,
    variant: AuroraChipVariant = AuroraChipVariant.Tonal,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(20.dp)
    val isFilled = variant == AuroraChipVariant.Filled
    val isOutlined = variant == AuroraChipVariant.Outlined
    val surfaceColor = if (isFilled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer
    val labelColor = if (isFilled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val secondaryColor = if (isFilled) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconTint = if (isFilled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .then(if (isFilled) Modifier.background(auroraGradient()) else Modifier.background(surfaceColor))
            .then(if (isOutlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
            .then(if (pressed) Modifier.alpha(0.85f) else Modifier)
            .then(
                if (checked != null) {
                    Modifier.toggleable(
                        value = checked,
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { onClick() },
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    )
                },
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
        } else if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondaryLabel != null) {
                Text(
                    text = secondaryLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
