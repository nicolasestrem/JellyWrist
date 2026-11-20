package com.example.jellywrist.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.example.jellywrist.R
import com.example.jellywrist.data.db.SyncOutcome
import com.example.jellywrist.ui.components.AuroraChip
import com.example.jellywrist.ui.components.SectionHeader
import com.example.jellywrist.ui.components.StorageGauge
import com.example.jellywrist.ui.components.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen — Aurora UI (PLAN.md §8 task 13).
 *
 * Scrollable sections via [ScalingLazyColumn] in a [ScreenScaffold] (scroll state
 * is wired to the time header):
 * 1. Storage — annular gauge (used / quota) + quota stepper (+/- 1 GB);
 * 2. Sync — charging only, Wi-Fi unmetered, periodicity;
 * 3. Sync log — tinted vector icon by outcome + summary;
 * 4. Account — user / server, then sign-out (error red).
 *
 * Shared `ui.components` (Aurora): [SectionHeader], [StorageGauge],
 * [AuroraChip], [formatBytes]. Wear Compose Material3 does not offer a `TextField`:
 * numeric settings (quota, periodicity) use +/- button steppers.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onQuotaChange: (Long) -> Unit,
    onRequiresChargingChange: (Boolean) -> Unit,
    onUnmeteredOnlyChange: (Boolean) -> Unit,
    onPeriodicityChange: (Int) -> Unit,
    onSignOut: () -> Unit,
) {
    val scrollState = rememberScalingLazyListState()

    ScreenScaffold(
        scrollState = scrollState,
        timeText = { TimeText() },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = scrollState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 40.dp,
                start = 12.dp,
                end = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // --- Storage ----------------------------------------------------
            item {
                SectionHeader(
                    title = stringResource(R.string.settings_section_storage),
                    icon = Icons.Rounded.Storage,
                )
            }
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StorageGauge(
                        usedBytes = state.usedBytes,
                        totalBytes = state.settings.quotaBytes,
                        center = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = formatBytes(state.usedBytes),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = formatBytes(state.settings.quotaBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_available,
                            formatBytes(state.availableDiskBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                QuotaStepper(
                    quotaBytes = state.settings.quotaBytes,
                    onQuotaChange = onQuotaChange,
                )
            }

            // --- Sync -------------------------------------------------------
            item {
                SectionHeader(
                    title = stringResource(R.string.settings_section_sync),
                    icon = Icons.Rounded.Sync,
                )
            }
            item {
                ToggleChip(
                    label = stringResource(R.string.settings_charging_only),
                    checked = state.settings.requiresCharging,
                    onCheckedChange = onRequiresChargingChange,
                    leadingIcon = Icons.Rounded.BatteryChargingFull,
                )
            }
            item {
                ToggleChip(
                    label = stringResource(R.string.settings_unmetered_only),
                    checked = state.settings.unmeteredOnly,
                    onCheckedChange = onUnmeteredOnlyChange,
                    leadingIcon = Icons.Rounded.Wifi,
                )
            }
            item {
                PeriodicityStepper(
                    periodicityHours = state.settings.periodicityHours,
                    onPeriodicityChange = onPeriodicityChange,
                )
            }

            // --- Sync log ---------------------------------------------------
            if (state.syncLog.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.settings_section_log),
                        icon = Icons.Rounded.History,
                    )
                }
                state.syncLog.forEach { entry ->
                    item {
                        SyncLogRow(entry)
                    }
                }
            }

            // --- Account ----------------------------------------------------
            item {
                SectionHeader(
                    title = stringResource(R.string.settings_section_account),
                    icon = Icons.Rounded.Person,
                )
            }
            item {
                AuroraChip(
                    label = state.userName ?: "—",
                    onClick = {},
                    secondaryLabel = state.serverName,
                    leadingIcon = Icons.Rounded.Cloud,
                    enabled = false,
                )
            }

            // --- Sign-out ---------------------------------------------------
            item {
                Spacer(Modifier.height(4.dp))
                if (state.signingOut) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_sign_out),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Button(
                        onClick = onSignOut,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text = stringResource(R.string.settings_sign_out))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Quota stepper: +/- buttons in 1 GB increments (1_073_741_824 bytes).
 *
 * On Wear OS, a finger slider is unreliable on a round screen; incremental
 * buttons are. Removal is bounded to 1 GB minimum (REQ-007 / PLAN.md §4.4).
 */
@Composable
private fun QuotaStepper(quotaBytes: Long, onQuotaChange: (Long) -> Unit) {
    val step = 1_073_741_824L // 1 GB
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { onQuotaChange((quotaBytes - step).coerceAtLeast(step)) }) {
            Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = stringResource(R.string.settings_decrease_quota),
            )
        }
        Text(
            text = formatBytes(quotaBytes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(onClick = { onQuotaChange(quotaBytes + step) }) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.settings_increase_quota),
            )
        }
    }
}

/**
 * Periodicity stepper: cycles through the usual steps 6 / 12 / 24 / 48 / 168 h
 * (weekly). REQ-007. Displays the localized label `settings_periodicity`.
 */
@Composable
private fun PeriodicityStepper(periodicityHours: Int, onPeriodicityChange: (Int) -> Unit) {
    val steps = listOf(6, 12, 24, 48, 168)
    val currentIndex = steps.indexOfFirst { it >= periodicityHours }
        .let { if (it < 0) steps.lastIndex else it }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { onPeriodicityChange(steps[(currentIndex - 1).coerceAtLeast(0)]) }) {
            Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = stringResource(R.string.settings_decrease_periodicity),
            )
        }
        Text(
            text = stringResource(R.string.settings_periodicity, periodicityHours),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(onClick = { onPeriodicityChange(steps[(currentIndex + 1).coerceAtMost(steps.lastIndex)]) }) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.settings_increase_periodicity),
            )
        }
    }
}

/**
 * Toggle row: [AuroraChip] (leading icon + label) with a Wear M3 [SwitchButton]
 * in the `trailing` slot. The chip body also toggles the switch for
 * a comfortable touch zone on the round screen.
 */
@Composable
private fun ToggleChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leadingIcon: ImageVector,
) {
    AuroraChip(
        label = label,
        onClick = { onCheckedChange(!checked) },
        leadingIcon = leadingIcon,
        trailing = {
            // The label is carried by the chip body; [SwitchButton] 1.6.2
            // requires a `label` slot, so we pass an empty one to keep only
            // the round toggle in the trailing slot.
            SwitchButton(
                checked = checked,
                onCheckedChange = onCheckedChange,
                label = {},
            )
        },
    )
}

/**
 * Sync log row: tinted vector icon by outcome ([SyncOutcome]),
 * start time + stats (+/-) on the main line, and `message` as a secondary
 * summary (bodySmall / onSurfaceVariant). Replaces unicode glyphs (✓ ◐ ⏸ ✗)
 * with Material icons (DEC-005, PLAN.md §4.2 point 7).
 */
@Composable
private fun SyncLogRow(entry: SyncLogEntry) {
    // "HH:mm" is locale-independent (24 h, digits); Locale.US avoids the
    // non-observable locale read in a composable (NonObservableLocale rule).
    val dateFormat = SimpleDateFormat("HH:mm", Locale.US)
    val time = dateFormat.format(Date(entry.startedAtEpochMs))

    val (outcomeIcon, outcomeTint) = when (entry.outcome) {
        SyncOutcome.SUCCESS -> Icons.Rounded.Check to MaterialTheme.colorScheme.tertiary
        SyncOutcome.PARTIAL -> Icons.Rounded.Sync to MaterialTheme.colorScheme.primary
        SyncOutcome.PAUSED -> Icons.Rounded.CloudOff to MaterialTheme.colorScheme.onSurfaceVariant
        SyncOutcome.FAILED -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = outcomeIcon,
            contentDescription = null,
            tint = outcomeTint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = "$time  +${entry.added} −${entry.removed}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
