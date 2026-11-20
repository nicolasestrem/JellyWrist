package com.example.jellywrist.mobile.ui

import androidx.annotation.StringRes
import com.example.jellywrist.mobile.R
import com.example.jellywrist.wire.WireAck
import com.example.jellywrist.wire.WireErrors
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Explicit lifecycle of a remote watch operation (sync now, pin, unpin).
 *
 * These commands used to be fire-and-forget: the ViewModel discarded the `Result`, so a
 * `WATCH_UNREACHABLE` failure or a 15 s timeout produced no feedback at all — the tap
 * looked inert. Every remote action now moves through these states and the UI renders
 * each of them.
 */
sealed interface OpState {
    data object Idle : OpState
    data object InFlight : OpState

    /** For SYNC_NOW this means **queued** on the watch, not completed — label it as such. */
    data object Success : OpState
    data class Error(val code: String) : OpState
}

/** Folds a remote-command result into an [OpState], surfacing the watch's error code. */
fun Result<WireAck>.toOpState(): OpState = fold(
    onSuccess = { ack ->
        if (ack.ok) OpState.Success else OpState.Error(ack.error ?: WireErrors.INTERNAL)
    },
    onFailure = { OpState.Error(it.toWireErrorCode()) },
)

/**
 * Maps a failed remote exchange to a [WireErrors] code.
 *
 * `WatchStatusRepository` signals an unreachable node via `error(WireErrors.WATCH_UNREACHABLE)`
 * and an expired wait via [TimeoutCancellationException]; anything else is unexpected.
 */
fun Throwable.toWireErrorCode(): String = when {
    this is TimeoutCancellationException -> WireErrors.TIMEOUT
    message == WireErrors.WATCH_UNREACHABLE -> WireErrors.WATCH_UNREACHABLE
    else -> WireErrors.INTERNAL
}

/** User-facing message for a [WireErrors] code — shared by the setup and command flows. */
@StringRes
fun wireErrorRes(error: String): Int = when (error) {
    // One string, one meaning: the setup flow and the home card used to carry two resources
    // holding the identical sentence, which is why the screen printed it twice.
    WireErrors.WATCH_UNREACHABLE -> R.string.watch_status_no_answer
    WireErrors.NO_SESSION -> R.string.setup_error_no_session
    WireErrors.AUTH_FAILED -> R.string.setup_error_auth_failed
    WireErrors.QUICKCONNECT_UNAVAILABLE -> R.string.setup_error_qc_unavailable
    WireErrors.INVALID_URL -> R.string.setup_error_invalid_url
    WireErrors.UNREACHABLE -> R.string.setup_error_unreachable
    WireErrors.EXPIRED -> R.string.setup_error_expired
    WireErrors.TIMEOUT -> R.string.setup_error_timeout
    WireErrors.BAD_COMMAND -> R.string.setup_error_unknown
    else -> R.string.setup_error_unknown
}
