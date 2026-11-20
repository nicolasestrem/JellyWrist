package com.example.jellywrist.mobile.datalayer

import com.example.jellywrist.wire.PinCommand
import com.example.jellywrist.wire.SyncCommand
import com.example.jellywrist.wire.WatchStatus
import com.example.jellywrist.wire.WireAck
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.WireSyncBlockers
import com.example.jellywrist.wire.toWireBytes
import com.example.jellywrist.wire.fromWire
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that the status pushed immediately after the pin ACK is never lost. */
class WatchStatusRepositoryTest {

    @Test
    fun `status pushed immediately after the pin ack updates the cache`() = runTest {
        val link = FakeWatchLink()
        val repository = WatchStatusRepository(link)
        // An unpin does not trigger any network refresh on the watch: the STATUS_REPLY
        // follows the ACK without delay, before the phone has finished processing the latter.
        link.onSend = { path ->
            if (path == WirePaths.PIN) {
                link.reply(WirePaths.PIN_REPLY, WireAck(ok = true).toWireBytes())
                link.reply(WirePaths.STATUS_REPLY, WatchStatus(configured = true, userName = "Avery").toWireBytes())
            }
        }

        val ack = repository.pin(PinCommand("id", "ALBUM", "Album", pin = false)).getOrThrow()

        assertTrue(ack.ok)
        assertEquals("Avery", repository.status.value?.userName)
    }

    @Test
    fun `a refused pin leaves the cached status untouched`() = runTest {
        val link = FakeWatchLink()
        val repository = WatchStatusRepository(link)
        link.onSend = { path ->
            if (path == WirePaths.PIN) {
                link.reply(WirePaths.PIN_REPLY, WireAck(ok = false, error = "no_session").toWireBytes())
            }
        }

        val ack = repository.pin(PinCommand("id", "ALBUM", "Album", pin = true)).getOrThrow()

        assertEquals("no_session", ack.error)
        assertNull(repository.status.value)
    }

    @Test
    fun `an unreachable watch fails instead of hanging`() = runTest {
        val repository = WatchStatusRepository(FakeWatchLink(node = null))

        assertTrue(repository.refresh().isFailure)
    }

    @Test
    fun `no connected watch is exposed as such instead of a generic failure`() = runTest {
        val repository = WatchStatusRepository(FakeWatchLink(discovery = WatchDiscovery.NoWatchConnected))

        assertTrue(repository.refresh().isFailure)
        assertEquals(WatchDiscovery.NoWatchConnected, repository.discovery.value)
    }

    @Test
    fun `a connected watch without the app is distinguished from no watch at all`() = runTest {
        val repository = WatchStatusRepository(
            FakeWatchLink(discovery = WatchDiscovery.NodesWithoutApp(listOf("Test Watch"))),
        )

        assertTrue(repository.refresh().isFailure)
        assertEquals(
            WatchDiscovery.NodesWithoutApp(listOf("Test Watch")),
            repository.discovery.value,
        )
    }

    @Test
    fun `the transport listener is registered before any request is sent`() = runTest {
        val link = FakeWatchLink()
        val repository = WatchStatusRepository(link)
        link.onSend = { path ->
            if (path == WirePaths.STATUS_REQUEST) {
                link.reply(WirePaths.STATUS_REPLY, WatchStatus(configured = true).toWireBytes())
            }
        }

        repository.refresh().getOrThrow()

        // Registration is IPC in production: sending first would let a fast reply race it.
        assertEquals(listOf("listen", "send:${WirePaths.STATUS_REQUEST}"), link.events)
    }

    @Test
    fun `sync sends an explicit command and preserves each consent`() = runTest {
        val link = FakeWatchLink()
        val repository = WatchStatusRepository(link)
        link.onSend = { path ->
            if (path == WirePaths.SYNC_NOW) {
                link.reply(WirePaths.SYNC_REPLY, WireAck(ok = true).toWireBytes())
            }
        }

        repository.syncNow(allowMetered = true, allowLowBattery = true).getOrThrow()

        val command = link.sentPayloads.single().fromWire<SyncCommand>()
        assertTrue(command.allowMetered)
        assertTrue(command.allowLowBattery)
    }

    @Test
    fun `status polling stops when metered consent is required`() = runTest {
        val link = FakeWatchLink()
        val repository = WatchStatusRepository(link)
        var statusRequests = 0
        link.onSend = { path ->
            if (path == WirePaths.STATUS_REQUEST) {
                statusRequests++
                val status = if (statusRequests == 1) {
                    WatchStatus(configured = true, syncState = "QUEUED")
                } else {
                    WatchStatus(
                        configured = true,
                        syncState = "PAUSED",
                        syncBlocker = WireSyncBlockers.METERED_CONFIRMATION_REQUIRED,
                    )
                }
                link.reply(WirePaths.STATUS_REPLY, status.toWireBytes())
            }
        }

        repository.pollSyncStatus(previousLastSyncEpochMs = null).getOrThrow()

        assertEquals(2, statusRequests)
        assertEquals(
            WireSyncBlockers.METERED_CONFIRMATION_REQUIRED,
            repository.status.value?.syncBlocker,
        )
    }
}
