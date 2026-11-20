package com.example.jellywrist.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.jellywrist.R
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Byte-size formatting — single source of truth.
 *
 * Replaces the three private copies (`DownloadsScreen.formatBytes`,
 * `SettingsScreen.formatBytes`, `CollectionDetailScreen.formatCollectionBytes`)
 * that hardcoded the French units "Go/Mo/Ko" and therefore leaked into the
 * English build. Units are now localized via the `unit_bytes_*` resources.
 */
@Composable
fun formatBytes(bytes: Long): String =
    formatBytesWithUnits(
        bytes,
        gb = stringResource(R.string.unit_bytes_gb),
        mb = stringResource(R.string.unit_bytes_mb),
        kb = stringResource(R.string.unit_bytes_kb),
        b = stringResource(R.string.unit_bytes_b),
    )

/** Pure formatter (unit-testable); units are injected by [formatBytes]. */
fun formatBytesWithUnits(bytes: Long, gb: String, mb: String, kb: String, b: String): String {
    if (bytes <= 0L) return "0 $b"
    val units = arrayOf(b, kb, mb, gb)
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return StringFormat.format(value) + " " + units[digitGroups]
}

/** Thin bridge to `String.format` (kept separate to stay testable without Android). */
private object StringFormat {
    fun format(value: Double): String =
        // Locale.US: stable "." decimal separator regardless of country
        // (we want "1.2 GB", not "1,2").
        if (value >= 100.0) String.format(Locale.US, "%.0f", value)
        else String.format(Locale.US, "%.1f", value)
}

/**
 * Formats a duration in milliseconds as `m:ss` (or `h:mm:ss` beyond one hour).
 * Used for the player's elapsed/total time.
 */
fun formatDuration(ms: Long): String {
    if (ms < 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
