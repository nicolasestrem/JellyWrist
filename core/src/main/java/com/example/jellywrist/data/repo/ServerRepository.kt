package com.example.jellywrist.data.repo

import com.example.jellywrist.data.jellyfin.JellyfinBackend
import com.example.jellywrist.data.jellyfin.UrlNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jellyfin.sdk.api.operations.SystemApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server discovered on the LAN (SDK's UDP 7359 broadcast).
 *
 * @see ServerRepository.discoverServers
 */
data class DiscoveredServer(
    val address: String,
    val id: String,
    val name: String,
)

/**
 * Public metadata of a validated server (extracted from `GET /System/Info/Public`).
 */
data class ServerInfo(
    val baseUrl: String,
    val name: String,
    val version: String?,
    val id: String?,
    /** `true` if the installation wizard is finished (server usable). */
    val startupWizardCompleted: Boolean,
)

/**
 * Result of validating a server input.
 */
sealed interface ServerValidation {
    /** URL normalized and then successfully validated. */
    data class Valid(val server: ServerInfo) : ServerValidation

    /** The input cannot be normalized into a usable URL. */
    data class InvalidUrl(val input: String) : ServerValidation

    /** Usable URL but unreachable or not Jellyfin (HTTP error / deserialization). */
    data class Unreachable(val url: String, val cause: Throwable) : ServerValidation
}

/**
 * Access to Jellyfin servers: local discovery (UDP 7359) and validation of a manual
 * input via `GET /System/Info/Public` (PLAN.md §3.1).
 *
 * Depends on [JellyfinBackend] (SDK abstraction) rather than [org.jellyfin.sdk.Jellyfin]
 * directly: testable on the JVM without a `Context`.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val backend: JellyfinBackend,
) {
    /**
     * Automatic discovery of Jellyfin servers on the local network, mapped to the
     * [DiscoveredServer] domain type. Not tested on the JVM (real UDP broadcast).
     */
    fun discoverServers(): Flow<DiscoveredServer> =
        backend.discoverServers().map { info ->
            DiscoveredServer(address = info.address, id = info.id, name = info.name)
        }

    /**
     * Normalizes [rawUrl] into candidates and then probes them in order via
     * `GET /System/Info/Public` (no auth required) — the first one that responds wins.
     *
     * The scheme is therefore **determined by the server**, not assumed: `jellyfin.example.com` tries
     * `https://jellyfin.example.com`, `https://jellyfin.example.com:8096`, `http://jellyfin.example.com:8096` then
     * `http://jellyfin.example.com` (PLAN.md §3.1).
     *
     * **No cleartext fallback after a TLS failure**: if an HTTPS candidate reaches the
     * handshake but the certificate is rejected (self-signed, not pinned), probing
     * STOPS and the TLS failure is returned — it is what triggers the pinning
     * proposal (DEC-006). Continuing to `http://` would amount to silently downgrading
     * the connection of a server that exposes both, bypassing certificate approval.
     * The HTTP fallback is only legitimate when nothing is listening over TLS (connection
     * refused, timeout, unknown host).
     */
    suspend fun validateServer(rawUrl: String): ServerValidation {
        val candidates = UrlNormalizer.candidates(rawUrl)
        if (candidates.isEmpty()) return ServerValidation.InvalidUrl(rawUrl)

        var lastFailure: ServerValidation.Unreachable? = null

        for (url in candidates) {
            try {
                val info = SystemApi(backend.apiClient(url)).getPublicSystemInfo().content
                return ServerValidation.Valid(
                    ServerInfo(
                        baseUrl = url,
                        name = info.serverName ?: url,
                        version = info.version,
                        id = info.id,
                        startupWizardCompleted = info.startupWizardCompleted ?: true,
                    ),
                )
            } catch (e: CancellationException) {
                throw e // never swallow cancellation (preserves message/cause)
            } catch (e: Exception) {
                val failure = ServerValidation.Unreachable(url, e)
                // Distinguish two families of TLS failure:
                //  - TRUST failure (CertificateException in the chain): the server
                //    speaks TLS but its certificate is rejected (self-signed, not pinned) —
                //    STOP, pinning proposal, never a cleartext downgrade;
                //  - PROTOCOL failure ("plaintext connection?" on an HTTP port): there
                //    simply is no TLS here — the next candidate is allowed to try,
                //    otherwise http/https auto-detection would never detect a
                //    cleartext server.
                val isTrustFailure = generateSequence<Throwable>(e) { it.cause }
                    .any { it is java.security.cert.CertificateException }
                if (isTrustFailure) return failure
                lastFailure = failure
            }
        }

        return lastFailure ?: ServerValidation.InvalidUrl(rawUrl)
    }
}
