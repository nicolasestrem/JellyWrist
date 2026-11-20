package com.example.jellywrist.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import com.example.jellywrist.ui.theme.auroraGradient

/**
 * Player progress bar — draggable / tappable.
 *
 * Wear Material3 doesn't expose a `Slider`, so we draw the track on a [Canvas]
 * (gray track + signature-gradient played portion + round thumb) and convert
 * the touch position to milliseconds via [onSeek]. Continuous drag and tap are
 * handled in the same [pointerInput].
 *
 * `durationMs == 0` (no media loaded) → bar disabled, non-interactive.
 */
@Composable
fun AuroraSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = durationMs > 0L,
) {
    val safeDuration = maxOf(durationMs, 1L)
    val fraction = if (!enabled) 0f else (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    // rememberUpdatedState instead of pointerInput keys: onSeek arrives as an unstable
    // viewModel method reference and durationMs changes with every 1 Hz position tick —
    // keying pointerInput on them tore the gesture detector down every second during
    // playback, dropping drags started near a tick.
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val trackColor = MaterialTheme.colorScheme.surfaceContainer
    val barHeight = 6.dp
    val thumbDiameter = 12.dp
    val rowHeight = maxOf(barHeight, thumbDiameter)
    val hitTargetHeight = 48.dp

    Box(
        modifier
            .fillMaxWidth()
            .height(hitTargetHeight)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (!enabled) disabled()
                setProgress { requestedFraction ->
                    if (!enabled) {
                        false
                    } else {
                        onSeek(seekPositionForFraction(requestedFraction, durationMs))
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = maxOf(size.width, 1)
                    // Duration captured per gesture: stable while the finger is down even
                    // if a track transition lands mid-drag.
                    val duration = currentDurationMs
                    val safe = maxOf(duration, 1L)
                    currentOnSeek(((down.position.x / width) * safe).toLong().coerceIn(0L, duration))
                    while (true) {
                        val event = awaitPointerEvent()
                        val anyPressed = event.changes.any { it.pressed }
                        event.changes.forEach { change ->
                            currentOnSeek(((change.position.x / width) * safe).toLong().coerceIn(0L, duration))
                            change.consume()
                        }
                        if (!anyPressed) break
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
            val centerY = size.height / 2f
            val barPx = barHeight.toPx()
            val top = centerY - barPx / 2f
            val corner = CornerRadius(barPx / 2f)
            // Background track.
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, top),
                size = Size(size.width, barPx),
                cornerRadius = corner,
            )
            // Played portion — signature gradient.
            val filledWidth = size.width * fraction
            if (filledWidth > 0f) {
                drawRoundRect(
                    brush = auroraGradient(),
                    topLeft = Offset(0f, top),
                    size = Size(filledWidth, barPx),
                    cornerRadius = corner,
                )
            }
            // Thumb.
            val thumbR = thumbDiameter.toPx() / 2f
            drawCircle(
                brush = auroraGradient(),
                radius = thumbR,
                center = Offset(size.width * fraction, centerY),
            )
        }
    }
}

internal fun seekPositionForFraction(fraction: Float, durationMs: Long): Long {
    if (durationMs <= 0L) return 0L

    val clampedFraction = fraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    return (clampedFraction * durationMs).toLong().coerceIn(0L, durationMs)
}
