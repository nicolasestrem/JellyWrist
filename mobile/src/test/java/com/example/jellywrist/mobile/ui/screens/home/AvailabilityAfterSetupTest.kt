package com.example.jellywrist.mobile.ui.screens.home

import com.example.jellywrist.mobile.datalayer.WatchDiscovery
import com.example.jellywrist.wire.WireErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a finished setup attempt is allowed to conclude about the link.
 *
 * Setup used to discard its own discovery, so the card kept whatever the last status refresh
 * had decided — telling a user the watch app was missing throughout an exchange with a watch
 * that had just answered. Publishing the attempt's result fixes that, but only some outcomes
 * actually prove something, and this is where that judgement lives.
 *
 * These are pure functions rather than assertions on `HomeViewModel` because the failures that
 * matter here are unreachable in a JVM test: `runSetup` reads `MobilePrefs` from DataStore on a
 * real dispatcher before it can produce them, so virtual time never gets past it.
 */
class AvailabilityAfterSetupTest {

    private val found = WatchDiscovery.Found("watch-node", "Test Watch")

    @Test
    fun `a watch that replied is reachable even though setup failed`() {
        // AUTH_FAILED comes from the phone's Quick Connect call, which only happens after the
        // watch has sent its code: the link is proven good and the app is proven installed.
        assertEquals(
            WatchAvailability.Reachable,
            availabilityAfterFailedSetup(WireErrors.AUTH_FAILED, found),
        )
    }

    @Test
    fun `a watch-reported error is also proof the watch answered`() {
        assertEquals(
            WatchAvailability.Reachable,
            availabilityAfterFailedSetup(WireErrors.EXPIRED, found),
        )
    }

    @Test
    fun `a timeout against a discovered watch means it did not answer`() {
        assertEquals(
            WatchAvailability.NotAnswering,
            availabilityAfterFailedSetup(WireErrors.TIMEOUT, found),
        )
    }

    @Test
    fun `an unreachable watch is named by the discovery that failed`() {
        assertEquals(
            WatchAvailability.AppMissing("Test Watch"),
            availabilityAfterFailedSetup(
                WireErrors.WATCH_UNREACHABLE,
                WatchDiscovery.NodesWithoutApp(listOf("Test Watch")),
            ),
        )
        assertEquals(
            WatchAvailability.NoWatchConnected,
            availabilityAfterFailedSetup(WireErrors.WATCH_UNREACHABLE, WatchDiscovery.NoWatchConnected),
        )
    }

    @Test
    fun `no session proves nothing about the link and changes nothing`() {
        // Raised before discovery runs, so the previous diagnosis is still the best one held.
        assertNull(availabilityAfterFailedSetup(WireErrors.NO_SESSION, null))
        assertNull(availabilityAfterFailedSetup(WireErrors.NO_SESSION, found))
    }

    @Test
    fun `a discovery that threw is reported as a discovery failure`() {
        assertEquals(
            WatchAvailability.DiscoveryFailed,
            availabilityAfterFailedSetup(WireErrors.WATCH_UNREACHABLE, null),
        )
    }
}
