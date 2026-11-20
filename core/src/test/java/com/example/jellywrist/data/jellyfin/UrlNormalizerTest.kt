package com.example.jellywrist.data.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL normalization tests by candidates (PLAN.md §3.1, reinforced 2026-08-08).
 *
 * Central contract: **the scheme is never assumed** — without an explicit scheme, the
 * candidate list probes HTTPS first (443 then 8096), then HTTP (8096 then 80), and
 * the server decides. With an explicit scheme, the user's choice is
 * respected to the letter.
 */
class UrlNormalizerTest {

    // ── Candidates: scheme determined by probing ────────────────────────

    @Test
    fun `bare hostname probes https first then http`() {
        assertEquals(
            listOf(
                "https://jelly.example.com",
                "https://jelly.example.com:8096",
                "http://jelly.example.com:8096",
                "http://jelly.example.com",
            ),
            UrlNormalizer.candidates("jelly.example.com"),
        )
    }

    @Test
    fun `bare ip probes the same ladder`() {
        assertEquals(
            listOf(
                "https://192.0.2.10",
                "https://192.0.2.10:8096",
                "http://192.0.2.10:8096",
                "http://192.0.2.10",
            ),
            UrlNormalizer.candidates("192.0.2.10"),
        )
    }

    @Test
    fun `explicit port without scheme probes both schemes on that port`() {
        assertEquals(
            listOf("https://192.0.2.10:8096", "http://192.0.2.10:8096"),
            UrlNormalizer.candidates("192.0.2.10:8096"),
        )
    }

    @Test
    fun `explicit scheme yields a single candidate`() {
        assertEquals(listOf("http://192.0.2.10:8096"), UrlNormalizer.candidates("http://192.0.2.10"))
        assertEquals(listOf("https://jelly.example.com"), UrlNormalizer.candidates("https://jelly.example.com"))
    }

    // ── Robustesse de saisie (claviers de montre agressifs) ─────────────

    @Test
    fun `uppercase scheme and host are lowercased`() {
        // Gboard capitalizes ("Https://Exemple.tv"): the input must remain valid.
        assertEquals(listOf("https://jellyfin.example.com"), UrlNormalizer.candidates("Https://jellyfin.example.com"))
        assertEquals(listOf("http://192.0.2.10:8096"), UrlNormalizer.candidates("HTTP://192.0.2.10:8096"))
    }

    @Test
    fun `inner spaces are removed`() {
        // A keyboard suggestion may insert a space: "jellyfin. example.com".
        assertEquals(
            "https://jellyfin.example.com",
            UrlNormalizer.candidates("jellyfin. example.com").first(),
        )
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(listOf("http://192.0.2.10:8096"), UrlNormalizer.candidates("  http://192.0.2.10  "))
    }

    @Test
    fun `trailing slash is stripped`() {
        assertEquals("https://jellyfin", UrlNormalizer.candidates("jellyfin/").first())
        assertEquals(listOf("http://localhost:8096"), UrlNormalizer.candidates("http://localhost:8096/"))
    }

    @Test
    fun `path is preserved on every candidate`() {
        assertTrue(UrlNormalizer.candidates("exemple.com/jellyfin").all { it.endsWith("/jellyfin") })
        assertEquals(
            listOf("http://192.0.2.10:8096/jellyfin"),
            UrlNormalizer.candidates("http://192.0.2.10/jellyfin"),
        )
    }

    @Test
    fun `userinfo query and fragment are stripped from base url`() {
        assertEquals(
            listOf("http://192.0.2.10:8096"),
            UrlNormalizer.candidates("http://user:pass@192.0.2.10?x=1#frag"),
        )
    }

    // ── Rejections ───────────────────────────────────────────────────────────

    @Test
    fun `blank input yields no candidate`() {
        assertTrue(UrlNormalizer.candidates("").isEmpty())
        assertTrue(UrlNormalizer.candidates("   ").isEmpty())
    }

    @Test
    fun `unsupported scheme yields no candidate`() {
        assertTrue(UrlNormalizer.candidates("ftp://server").isEmpty())
    }

    @Test
    fun `scheme without host yields no candidate`() {
        assertTrue(UrlNormalizer.candidates("http://").isEmpty())
    }

    // ── normalize: single-URL compat ──────────────────────────────────────

    @Test
    fun `normalize returns the preferred candidate`() {
        assertEquals("https://jelly.example.com", UrlNormalizer.normalize("jelly.example.com"))
        assertEquals("http://192.0.2.10:8096", UrlNormalizer.normalize("http://192.0.2.10"))
    }

    @Test
    fun `normalize rejects unusable input`() {
        assertNull(UrlNormalizer.normalize(""))
        assertNull(UrlNormalizer.normalize("ftp://server"))
    }
}
