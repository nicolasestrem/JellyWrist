package com.example.jellywrist.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
 * Ring-shaped storage gauge — signature-gradient arc over a gray track.
 *
 * Hero component of the Downloads screen and the Storage section of Settings.
 * The center is left open ([center]) so each screen can place its own label
 * there (used size + caption).
 *
 * @param usedBytes  "Consumed" value (drawn arc).
 * @param totalBytes Total value (full arc = 100%).
 */
@Composable
fun StorageGauge(
    usedBytes: Long,
    totalBytes: Long,
    modifier: Modifier = Modifier,
    diameter: Dp = 92.dp,
    stroke: Dp = 9.dp,
    center: @Composable BoxScope.() -> Unit,
) {
    val fraction = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
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
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            if (fraction > 0f) {
                drawArc(
                    brush = auroraGradient(),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        center()
    }
}
