package com.example.jellywrist.data.jellyfin

import java.net.URI

/**
 * Normalizes a server URL input (often incomplete on a watch screen) into an ordered
 * list of **candidates** to probe.
 *
 * Rules (PLAN.md §3.1 — "aggressive normalization", strengthened on 2026-08-08):
 *  - spaces removed, scheme and host case-insensitive (watch keyboards capitalize
 *    aggressively: `Https://Example.tv` must work);
 *  - **scheme guessed by probing**: without an explicit scheme, the app tries in turn
 *    `https://host` (public domain, 443), `https://host:8096`,
 *    `http://host:8096` (typical LAN) then `http://host` — the server decides,
 *    not a single guess;
 *  - with an explicit scheme, a single candidate (the user's choice is respected),
 *    port 8096 added for `http` without a port;
 *  - userinfo, query and fragment removed; trailing slash removed;
 *  - empty list if the host is missing or the input is unparsable.
 *
 * The actual validation (is the server alive? is it Jellyfin?) is done candidate by
 * candidate via `GET /System/Info/Public` by
 * [com.example.jellywrist.data.repo.ServerRepository.validateServer].
 *
 * Limitation: does not reconstruct literal IPv6 addresses (a rare case for Jellyfin on a LAN).
 */
object UrlNormalizer {

    /** Default HTTP(S) port of a self-hosted Jellyfin server. */
    private const val JELLYFIN_PORT = 8096

    /**
     * Candidates to probe, in order of preference (HTTPS first — cleartext traffic is
     * forbidden in release builds, see network_security_config).
     */
    fun candidates(raw: String): List<String> {
        val trimmed = raw.trim().replace(" ", "")
        if (trimmed.isEmpty()) return emptyList()

        val explicitScheme = trimmed.contains("://")
        if (explicitScheme) {
            val lowered = lowercaseScheme(trimmed)
            if (!lowered.startsWith("http://") && !lowered.startsWith("https://")) return emptyList()
            return listOfNotNull(rebuild(lowered))
        }

        val parsed = rebuild("https://$trimmed") ?: return emptyList()
        val uri = URI(parsed)
        val host = uri.host.lowercase()
        val explicitPort = uri.port != -1
        val path = uri.rawPath.orEmpty().trimEnd('/')

        return if (explicitPort) {
            // `host:8096`: the port is a choice; only the scheme remains to be determined.
            listOf("https://$host:${uri.port}$path", "http://$host:${uri.port}$path")
        } else {
            listOf(
                "https://$host$path",
                "https://$host:$JELLYFIN_PORT$path",
                "http://$host:$JELLYFIN_PORT$path",
                "http://$host$path",
            )
        }
    }

    /**
     * Single best candidate — compat for callers that only need one URL
     * (display, pre-filling). Validation must go through [candidates].
     */
    fun normalize(raw: String): String? = candidates(raw).firstOrNull()

    /** Lowercases the scheme without touching the rest (the path is case-sensitive). */
    private fun lowercaseScheme(input: String): String {
        val idx = input.indexOf("://")
        return input.take(idx).lowercase() + input.substring(idx)
    }

    /** Rebuilds a cleaned-up `scheme://host[:port][path]`, or `null` if unparsable. */
    private fun rebuild(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty()) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty().trimEnd('/')

        val port = when {
            uri.port != -1 -> ":${uri.port}"
            scheme == "http" -> ":$JELLYFIN_PORT"
            else -> "" // https: implicit 443
        }
        return "$scheme://$host$port$path"
    }
}
