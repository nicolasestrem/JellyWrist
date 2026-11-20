package com.example.jellywrist.data.net

import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * [X509TrustManager] that accepts a self-signed certificate only if its SHA-256
 * fingerprint appears in [pins], and delegates to system validation for all others.
 *
 * **Design (DEC-006):**
 * - Pinning is opt-in: the user explicitly accepts the certificate during
 *   connection (PLAN.md §3.1). TLS is never globally disabled.
 * - [PinningTrustManager] does not know the hostname (the `checkServerTrusted` API does
 *   not receive it): it only checks the leaf certificate's fingerprint. Hostname
 *   verification is performed separately by OkHttp (default `HostnameVerifier`),
 *   which prevents an attacker from presenting a pinned certificate for another host.
 * - This TrustManager is combined with an OkHttp [okhttp3.CertificatePinner] for
 *   defense in depth: the pinner checks the hostname AND the fingerprint after the
 *   TLS handshake.
 *
 * **TLS validation order:**
 * 1. `PinningTrustManager.checkServerTrusted` — accepts if the fingerprint is pinned,
 *    otherwise delegates to the system TrustManager.
 * 2. OkHttp `CertificatePinner.check` — checks hostname + fingerprint.
 * 3. OkHttp `HostnameVerifier.verify` — checks hostname ↔ certificate.
 *
 * @param delegate Default system TrustManager (public CAs).
 * @param pinsProvider Provides the **current** set of explicitly accepted SHA-256
 *   fingerprints (hex, lowercase) — re-read at every TLS handshake, so that a pin accepted
 *   in the settings applies without rebuilding the HTTP clients.
 */
class PinningTrustManager private constructor(
    private val delegate: X509TrustManager,
    private val pinsProvider: () -> Set<String>,
) : X509TrustManager by delegate {

    /**
     * If default validation fails (certificate not signed by a known CA),
     * checks whether the leaf certificate's fingerprint matches an explicit pin.
     * If so, the connection is accepted; otherwise, the original exception is rethrown.
     */
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            delegate.checkServerTrusted(chain, authType)
        } catch (_: Exception) {
            // Default validation failed (typically: self-signed certificate).
            // Check whether the leaf certificate is explicitly pinned.
            val leaf = chain.firstOrNull() ?: throw IllegalArgumentException("Empty chain")
            val sha256 = sha256Hex(leaf)
            if (sha256 !in pinsProvider()) {
                throw java.security.cert.CertificateException(
                    "Certificate not pinned (SHA-256: $sha256)"
                )
            }
            // Fingerprint accepted — the hostname will be verified separately by OkHttp.
        }
    }

    /**
     * Creates an `SSLContext` initialized with this TrustManager.
     *
     * The returned `SSLContext` must be used to build an
     * `OkHttpClient.Builder().sslSocketFactory(ctx.socketFactory, this)`.
     */
    fun sslContext(): SSLContext {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(this), null)
        return ctx
    }

    companion object {
        /** Computes a certificate's SHA-256 fingerprint as lowercase hex, without separators. */
        fun sha256Hex(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(cert.encoded)
            return hash.joinToString("") { "%02x".format(it) }
        }

        /**
         * Creates a [PinningTrustManager] around the default system TrustManager.
         *
         * @param pins Accepted SHA-256 fingerprints (lowercase hex). Empty set = no pinning,
         *   the delegate handles everything (behavior identical to the system).
         */
        fun create(pinsProvider: () -> Set<String>): PinningTrustManager {
            val defaultTm = defaultSystemTrustManager()
            return PinningTrustManager(defaultTm, pinsProvider)
        }

        /**
         * Extracts the system's default [X509TrustManager].
         *
         * Uses [X509ExtendedTrustManager] if available (Android 7+), otherwise
         * [X509TrustManager].
         */
        private fun defaultSystemTrustManager(): X509TrustManager {
            val tf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tf.init(null as java.security.KeyStore?)
            return tf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()
        }
    }
}
