package com.example.jellywrist.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Certificate pinning tests (DEC-006, PLAN.md §3.1).
 *
 * Fixture: stable self-signed certificate (CN=jellyfin.local) generated once with
 * `keytool` and embedded as PEM — tests are deterministic and offline.
 * The expected SHA-256 fingerprint comes from `keytool -printcert` on the same file.
 *
 * Properties enforced:
 * - the fingerprint computation matches that of standard tools;
 * - without a pin, a self-signed certificate is rejected (system trust only);
 * - with the correct pin, it is accepted; with a wrong pin, rejected;
 * - the pin is re-read dynamically (a pin accepted mid-session takes effect);
 * - [PinRegistry] normalizes case and separators.
 */
class CertificatePinningTest {

    private val expectedFingerprint =
        "4657279f5bd73d10cbe8c4fb1b5a309b9a173b2f835804be7cf9bd60e16cfd65"

    private val certificate: X509Certificate by lazy {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(PEM.toByteArray())) as X509Certificate
    }

    @Test
    fun `l empreinte SHA-256 correspond a celle de keytool`() {
        assertEquals(expectedFingerprint, PinningTrustManager.sha256Hex(certificate))
    }

    @Test
    fun `sans pin un certificat auto-signe est rejete`() {
        val tm = PinningTrustManager.create { emptySet() }
        try {
            tm.checkServerTrusted(arrayOf(certificate), "RSA")
            fail("a self-signed without pin must be rejected — never implicit trust")
        } catch (_: CertificateException) {
            // expected: system trust alone applies (DEC-006)
        }
    }

    @Test
    fun `avec le bon pin le certificat auto-signe est accepte`() {
        val tm = PinningTrustManager.create { setOf(expectedFingerprint) }
        // Must not throw: the user explicitly approved this fingerprint.
        tm.checkServerTrusted(arrayOf(certificate), "RSA")
    }

    @Test
    fun `avec un pin different le certificat est rejete`() {
        val tm = PinningTrustManager.create { setOf("deadbeef".repeat(8)) }
        try {
            tm.checkServerTrusted(arrayOf(certificate), "RSA")
            fail("a different fingerprint must never pass")
        } catch (_: CertificateException) {
            // attendu
        }
    }

    @Test
    fun `le pin est relu a chaque poignee de main`() {
        // A pin accepted in settings must apply without rebuilding the HTTP client:
        // the provider is consulted on each validation.
        var pins = emptySet<String>()
        val tm = PinningTrustManager.create { pins }

        try {
            tm.checkServerTrusted(arrayOf(certificate), "RSA")
            fail("rejected before pinning")
        } catch (_: CertificateException) {
            // attendu
        }

        pins = setOf(expectedFingerprint)
        tm.checkServerTrusted(arrayOf(certificate), "RSA") // accepted after
    }

    @Test
    fun `PinRegistry normalise casse et separateurs`() {
        val registry = PinRegistry()
        registry.update("46:57:27:9F:5B:D7:3D:10:CB:E8:C4:FB:1B:5A:30:9B:9A:17:3B:2F:83:58:04:BE:7C:F9:BD:60:E1:6C:FD:65")
        assertEquals(setOf(expectedFingerprint), registry.pins)

        registry.update(null)
        assertTrue("null = back to system trust only", registry.pins.isEmpty())
    }

    @Test
    fun `un pin a l'essai survit aux reemissions des reglages`() {
        val registry = PinRegistry()
        registry.update(ACCEPTED_FINGERPRINT)
        registry.stage(expectedFingerprint)

        // The settings collector re-emits on any change — periodicity, Wi-Fi
        // only, quota. An in-flight validation must not lose its certificate:
        // the next TLS handshake would fail on the old fingerprint.
        registry.update(ACCEPTED_FINGERPRINT)
        assertEquals(setOf(expectedFingerprint), registry.pins)

        // End of trial without persistence: back to the accepted fingerprint.
        registry.clearStaged()
        assertEquals(setOf(ACCEPTED_FINGERPRINT), registry.pins)
    }

    private companion object {
        /** "Already accepted" fingerprint, distinct from [expectedFingerprint]. */
        const val ACCEPTED_FINGERPRINT = "1122334455667788990011223344556677889900112233445566778899001122"

        val PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDBzCCAe+gAwIBAgIICsS3MxVmDmowDQYJKoZIhvcNAQELBQAwMjEXMBUGA1UE
            ChMOSmVsbHlXYXRjaFRlc3QxFzAVBgNVBAMTDmplbGx5ZmluLmxvY2FsMB4XDTI2
            MDgwODE0MjgxOFoXDTM2MDgwNTE0MjgxOFowMjEXMBUGA1UEChMOSmVsbHlXYXRj
            aFRlc3QxFzAVBgNVBAMTDmplbGx5ZmluLmxvY2FsMIIBIjANBgkqhkiG9w0BAQEF
            AAOCAQ8AMIIBCgKCAQEAsklF1Ka9W19KXIcbFZoE0CtNaqI7E6vlBqYsGscANp29
            IoDHUkW/oKt+VwUUe2306lDs1ShhHmTSxdHpu69qWu1D4gNtduSkvVhr5v7zYhU4
            SbGuG0sr5z82uh9C2c2ZRbbErSd9LSogme9udF3UrlmMf9XdVWrBjJoHQKFk01kY
            GEfjislaVMclz8BsQ+Wr46W2VG5UgXAqyBcrAyvwUYO1AhZoJyEsLABkezH4Hegz
            gZb9XmZG3AKZf8FxlgDNRILenqVeg0Nxlkg+yWNBw04P+76Qs3Cjg/mzC31Q1Qcd
            M9j9zKPrJK6TpkXanFi2DcQGND4hj6Zf5+ZAPgssJQIDAQABoyEwHzAdBgNVHQ4E
            FgQUzoAcfB0ZvMCm2VL/uoyvH1h3300wDQYJKoZIhvcNAQELBQADggEBAHzH2L0l
            fNnBU/Ybn70hmloZTkpm8nFHadUgRdmUP9QUVqtamX28CGMyuCpsc4MK/VSclujU
            a7z2pa6ElzV8anIlUMwNmQGz2eRs82A1a1Efl2adpmRY1XwS476qxH/fFzzUBP7B
            wZV52zGF1C5ZS8Ghiol0mGKwyOy5CJqOR9ruqyGosmPIXd3qlJSgt0dMMWSx3PSz
            Gd5kgRcSKRaWfCxK1F21klJZqXikWYJLOLAg02NQV6ewcXk8f3Jtazw3Bmo8R7y6
            vSs8LFB3F3Kicyh1wn/ZsoVzCHZaBzFOeWzJztf/MWIcO5dFZsAMbgM8MJNQ5hOP
            kvsZCxWGGdTCFt4=
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}
