package com.example.jellywrist.wire

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests of the wire protocol ([Wire.kt]).
 *
 * Verifies: (1) round-trip of each DTO via [toWireBytes]/[fromWire]; (2) tolerance of
 * unknown fields (backward compatibility — a newer peer that adds a field does not break
 * an older peer); (3) a malformed payload throws [SerializationException] (the Data Layer
 * receiver falls back to a silent drop).
 */
class WireTest {

    /** Typed helper: forces resolution of the reified T parameter (otherwise T=Any → SerializationException). */
    private inline fun <reified T> assertRoundTrip(value: T) {
        val encoded = value.toWireBytes()
        val decoded: T = encoded.fromWire()
        assertEquals(value, decoded)
    }

    @Test
    fun `SetupRequest round-trip with and without pinned certificate`() {
        assertRoundTrip(SetupRequest(serverUrl = "https://jelly.example.com", pinnedCertSha256 = "ABC123"))
        assertRoundTrip(SetupRequest(serverUrl = "http://192.0.2.5:8096"))
    }

    @Test
    fun `SetupQcCode round-trip`() {
        assertRoundTrip(SetupQcCode(code = "123456"))
    }

    @Test
    fun `SetupResult round-trip success`() {
        assertRoundTrip(SetupResult(ok = true, userName = "avery"))
    }

    @Test
    fun `SetupResult already configured preserves alreadyConfigured`() {
        assertRoundTrip(SetupResult(ok = true, userName = "avery", alreadyConfigured = true))
    }

    @Test
    fun `WireAck round-trip success and error`() {
        assertRoundTrip(WireAck(ok = true))
        assertRoundTrip(WireAck(ok = false, error = WireErrors.NO_SESSION))
    }

    @Test
    fun `PinCommand round-trip`() {
        assertRoundTrip(
            PinCommand(
                collectionId = "abc-123",
                collectionType = "PLAYLIST",
                name = "My playlist",
                trackCount = 42,
                pin = true,
            ),
        )
    }

    @Test
    fun `SyncCommand round-trip preserves per-request consent`() {
        assertRoundTrip(SyncCommand())
        assertRoundTrip(SyncCommand(allowMetered = true))
        assertRoundTrip(SyncCommand(allowLowBattery = true))
    }

    @Test
    fun `pre-low-battery SyncCommand payload defaults battery consent to false`() {
        val command: SyncCommand = """{"allowMetered":true}""".encodeToByteArray().fromWire()

        assertTrue(command.allowMetered)
        assertEquals(false, command.allowLowBattery)
    }

    @Test
    fun `WatchStatus round-trip with collections`() {
        assertRoundTrip(
            WatchStatus(
                configured = true,
                serverName = "My server",
                serverUrl = "https://jelly.example.com",
                userName = "avery",
                usedBytes = 123456789,
                quotaBytes = 1073741824,
                availableDiskBytes = 500000000,
                collections = listOf(
                    WirePinnedCollection(id = "c1", name = "Album A", type = "ALBUM", trackCount = 10, estimatedBytes = 50000000, lastSyncAtEpochMs = 1700000000000),
                    WirePinnedCollection(id = "c2", name = "Playlist B", type = "PLAYLIST", trackCount = 25, estimatedBytes = 120000000),
                ),
                lastSyncOutcome = "DOWNLOADED",
                lastSyncEpochMs = 1700000100000,
                appVersionName = "0.0.1",
            ),
        )
    }

    @Test
    fun `WatchStatus minimal (not configured) round-trip`() {
        assertRoundTrip(WatchStatus(configured = false))
    }

    @Test
    fun `v1 WatchStatus without sync fields remains compatible`() {
        val decoded: WatchStatus = """{"configured":true}""".encodeToByteArray().fromWire()

        assertEquals(null, decoded.syncState)
        assertEquals(null, decoded.syncBlocker)
    }

    @Test
    fun `tolerance of unknown fields - WatchStatus with future field`() {
        // A newer peer adds a field — the decoder ignores the unknown key.
        val json = """{"configured":true,"serverName":"srv","newFutureField":999}"""
        val decoded: WatchStatus = json.encodeToByteArray().fromWire()
        assertTrue(decoded.configured)
        assertEquals("srv", decoded.serverName)
    }

    @Test
    fun `malformed payload throws SerializationException`() {
        assertThrows(SerializationException::class.java) {
            val ignored: WatchStatus = "not json at all".encodeToByteArray().fromWire()
            Unit
        }
    }

    @Test
    fun `valid JSON but incompatible types throws SerializationException`() {
        assertThrows(SerializationException::class.java) {
            val ignored: WatchStatus = """{"configured":"not_a_bool"}""".encodeToByteArray().fromWire()
            Unit
        }
    }

    @Test
    fun `WireErrors codes stables`() {
        // These strings are a binary contract — a value change breaks an older peer.
        assertEquals("invalid_url", WireErrors.INVALID_URL)
        assertEquals("unreachable", WireErrors.UNREACHABLE)
        assertEquals("quickconnect_unavailable", WireErrors.QUICKCONNECT_UNAVAILABLE)
        assertEquals("expired", WireErrors.EXPIRED)
        assertEquals("auth_failed", WireErrors.AUTH_FAILED)
        assertEquals("no_session", WireErrors.NO_SESSION)
        assertEquals("watch_unreachable", WireErrors.WATCH_UNREACHABLE)
        assertEquals("bad_command", WireErrors.BAD_COMMAND)
        assertEquals(
            "metered_confirmation_required",
            WireSyncBlockers.METERED_CONFIRMATION_REQUIRED,
        )
        assertEquals(
            "low_battery_confirmation_required",
            WireSyncBlockers.LOW_BATTERY_CONFIRMATION_REQUIRED,
        )
    }

    @Test
    fun `WirePaths paths stables`() {
        assertEquals("/jellywrist/setup/v1", WirePaths.SETUP)
        assertEquals("/jellywrist/setup/qc/v1", WirePaths.SETUP_QC)
        assertEquals("/jellywrist/setup/result/v1", WirePaths.SETUP_RESULT)
        assertEquals("/jellywrist/status/v1", WirePaths.STATUS_REQUEST)
        assertEquals("/jellywrist/status/reply/v1", WirePaths.STATUS_REPLY)
        assertEquals("/jellywrist/pin/v1", WirePaths.PIN)
        assertEquals("/jellywrist/pin/reply/v1", WirePaths.PIN_REPLY)
        assertEquals("/jellywrist/sync/v1", WirePaths.SYNC_NOW)
        assertEquals("/jellywrist/sync/reply/v1", WirePaths.SYNC_REPLY)
    }
}
