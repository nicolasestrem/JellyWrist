package com.example.jellywrist.util

import android.util.Log

/**
 * Structured trace of the sync/download pipeline.
 *
 * The pipeline used to be completely silent: every early return, consent gate and
 * swallowed platform refusal between "the user tapped sync" and "bytes on disk" was
 * invisible on device, so a watch that never downloaded anything produced no evidence at
 * all. These traces are the diagnostic surface — one line per component boundary.
 *
 * **Never log a secret.** REQ-005 forbids the access token in logs, and a Jellyfin base
 * URL identifies a private server, so neither is ever passed here: item ids, counts,
 * states and booleans only. Callers pass exception *classes*, not response bodies.
 */
object SyncTrace {

    const val TAG = "JellySync"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message + error.describe())
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message + error.describe())
    }

    /**
     * Renders a failure as its class name and nothing else.
     *
     * Passing the throwable to `Log` would print its message and stack trace, and a
     * connection failure's message routinely carries the host name, IP address or full URL of
     * the user's server — private infrastructure this repository is not allowed to record
     * (AGENTS.md, REQ-005). The class alone still identifies the failure mode, which is what
     * these traces are for.
     */
    private fun Throwable?.describe(): String =
        if (this == null) "" else ": ${this.javaClass.name}"
}
