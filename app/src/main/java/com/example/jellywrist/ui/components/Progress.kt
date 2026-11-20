package com.example.jellywrist.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import com.example.jellywrist.ui.theme.auroraGradient

/**
 * Small progress ring with the signature gradient — for active downloads.
 *
 * `progress` is clamped to [0, 1]. Gray background ring (surfaceVariant) plus a
 * gradient-painted completed portion, starting at twelve o'clock (-90°).
 */
@Composable
fun GradientProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 24.dp,
    stroke: Dp = 3.dp,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceContainer
    Canvas(modifier = modifier.size(diameter)) {
        val strokePx = stroke.toPx()
        val inset = strokePx / 2f
        val arcSize = Size(size.width - strokePx, size.height - strokePx)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx),
        )
        if (clamped > 0f) {
            drawArc(
                brush = auroraGradient(),
                startAngle = -90f,
                sweepAngle = 360f * clamped,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    }
}
