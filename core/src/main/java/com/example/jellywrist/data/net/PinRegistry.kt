package com.example.jellywrist.data.net

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holder of the current certificate pin, read at every TLS handshake.
 *
 * Exists to break a circular dependency: the HTTP client needs the pins, but the settings
 * repository must not be consulted (DataStore, I/O) in the middle of a TLS handshake.
 * [pins] is hydrated at startup and then on every settings change
 * (see `JellyWristApp`). `@Volatile` is enough for reads: frequent reads, rare writes.
 *
 * Two fingerprints coexist: the one the user has **accepted** ([update], relayed by the
 * settings collector) and the one **on trial** ([stage]) for the duration of a validation
 * whose outcome is unknown. The latter takes precedence, and nothing persists it: a
 * provisioning attempt that fails or is cancelled only needs to [clearStaged] to give the
 * watch back the certificate it was working with.
 */
@Singleton
class PinRegistry @Inject constructor() {

    @Volatile
    var pins: Set<String> = emptySet()
        private set

    /** Fingerprint accepted and persisted in the settings. */
    private var accepted: String? = null

    /** Fingerprint on trial, never persisted; `null` when no validation is in progress. */
    private var staged: String? = null

    /**
     * Records the accepted fingerprint — the settings collector calls this on every
     * emission, including for settings unrelated to TLS. A fingerprint on trial is
     * therefore **not** affected: without this distinction, simply toggling
     * "Wi-Fi only" in the middle of a Quick Connect would revert the watch to the old
     * certificate and make the in-flight handshake fail.
     */
    @Synchronized
    fun update(pinnedSha256: String?) {
        accepted = pinnedSha256
        recompute()
    }

    /** Puts a certificate on trial: it takes precedence over [update] until [clearStaged]. */
    @Synchronized
    fun stage(pinnedSha256: String?) {
        staged = pinnedSha256
        recompute()
    }

    /** End of the trial: the watch reverts to the accepted fingerprint. */
    @Synchronized
    fun clearStaged() {
        staged = null
        recompute()
    }

    /** Normalizes to lowercase hex without separators — the format of [PinningTrustManager]. */
    private fun recompute() {
        val effective = staged ?: accepted
        pins = if (effective.isNullOrBlank()) {
            emptySet()
        } else {
            setOf(effective.lowercase().replace(":", "").replace(" ", ""))
        }
    }
}
