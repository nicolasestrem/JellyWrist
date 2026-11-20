package com.example.jellywrist.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Identity of a certificate presented by a server, shown to the user before pinning. */
data class PresentedCertificate(
    val sha256Hex: String,
    val subject: String,
    val issuer: String,
    val notAfterEpochMs: Long,
)

/**
 * Retrieves the leaf certificate presented by a server, **without ever trusting
 * it** (PLAN.md §3.1: the user sees the fingerprint and decides).
 *
 * The TrustManager used here accepts nothing: it **observes** the chain during the TLS
 * handshake and then the connection is closed without a single application byte being
 * exchanged. It is confined to this single socket — this is not a verification bypass
 * (DEC-006); it is the programmatic equivalent of "view certificate" in a browser.
 */
@Singleton
class CertificateFetcher @Inject constructor() {

    suspend fun fetch(baseUrl: String): Result<PresentedCertificate> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = URI(baseUrl)
            require(uri.scheme == "https") { "Pinning only applies to HTTPS" }
            val host = uri.host ?: error("Missing host in $baseUrl")
            val port = uri.port.takeIf { it > 0 } ?: DEFAULT_HTTPS_PORT

            var observed: Array<out X509Certificate>? = null
            val observer = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    observed = chain
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(observer), null)
            (ctx.socketFactory.createSocket(host, port) as SSLSocket).use { socket ->
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                socket.startHandshake()
            }

            val leaf = observed?.firstOrNull() ?: error("No certificate presented")
            PresentedCertificate(
                sha256Hex = PinningTrustManager.sha256Hex(leaf),
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                notAfterEpochMs = leaf.notAfter.time,
            )
        }
    }

    private companion object {
        const val DEFAULT_HTTPS_PORT = 443
        const val HANDSHAKE_TIMEOUT_MS = 10_000
    }
}
