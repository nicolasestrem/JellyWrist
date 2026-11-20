package com.example.jellywrist.network

import com.example.jellywrist.network.NetworkPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests of the pure network policy (PLAN.md §8 task 12, DEC-005 / REQ-007).
 *
 * Each observable transition has a defined action; the non-negotiable point is that
 * Wi-Fi loss during an active sync produces a **pause** (never a failure) and that a
 * manual request on a metered network produces a **warning** (never silent consumption).
 */
class NetworkPolicyTest {

    @Test
    fun `a manual pass that enqueued nothing releases the queue immediately`() {
        // The drain-based clearing cannot fire here: with nothing enqueued the progress flow
        // never emits again, so the override would outlive the run indefinitely.
        assertTrue(NetworkPolicy.manualRunEndsWithPass(hasOutstandingWork = false))
    }

    @Test
    fun `a manual pass that enqueued work keeps the queue until it drains`() {
        // `syncPass` returns once the tracks are registered, not once the bytes have landed.
        // Releasing here would let the charging observer pause the download the user just
        // authorised off the charger, inverting REQ-007's manual-sync contract.
        assertFalse(NetworkPolicy.manualRunEndsWithPass(hasOutstandingWork = true))
    }

    @Test
    fun `Wi-Fi loss during active sync pauses downloads`() {
        assertEquals(
            Action.PAUSE_DOWNLOADS,
            NetworkPolicy.decide(wasUnmetered = true, isUnmetered = false, syncRunning = true, manualRequested = false),
        )
    }

    @Test
    fun `Wi-Fi return during active sync resumes downloads`() {
        assertEquals(
            Action.RESUME_DOWNLOADS,
            NetworkPolicy.decide(wasUnmetered = false, isUnmetered = true, syncRunning = true, manualRequested = false),
        )
    }

    @Test
    fun `Wi-Fi loss without active sync does nothing`() {
        assertEquals(
            Action.CONTINUE,
            NetworkPolicy.decide(wasUnmetered = true, isUnmetered = false, syncRunning = false, manualRequested = false),
        )
    }

    @Test
    fun `manual request on a metered network requires a warning`() {
        assertEquals(
            Action.WARN_METERED,
            NetworkPolicy.decide(wasUnmetered = false, isUnmetered = false, syncRunning = false, manualRequested = true),
        )
    }

    @Test
    fun `manual request on Wi-Fi continues without warning`() {
        assertEquals(
            Action.CONTINUE,
            NetworkPolicy.decide(wasUnmetered = true, isUnmetered = true, syncRunning = false, manualRequested = true),
        )
    }

    @Test
    fun `stable network during active sync continues`() {
        assertEquals(
            Action.CONTINUE,
            NetworkPolicy.decide(wasUnmetered = true, isUnmetered = true, syncRunning = true, manualRequested = false),
        )
        assertEquals(
            Action.CONTINUE,
            NetworkPolicy.decide(wasUnmetered = false, isUnmetered = false, syncRunning = true, manualRequested = false),
        )
    }

    // ── Charging constraint (the pause storm) ───────────────────────────────

    @Test
    fun `an authorised manual run is never paused by the charging constraint`() {
        // Every ACTION_BATTERY_CHANGED broadcast re-evaluated this, so a watch off the
        // charger paused the queue again and again — including moments after the sync
        // worker had explicitly resumed it for a manual, user-authorised pass.
        assertEquals(
            Action.CONTINUE,
            NetworkPolicy.chargingAction(
                charging = false,
                requiresCharging = true,
                manualRunActive = true,
            ),
        )
    }

    @Test
    fun `off charger with the charging constraint pauses automatic downloads`() {
        assertEquals(
            Action.PAUSE_DOWNLOADS,
            NetworkPolicy.chargingAction(
                charging = false,
                requiresCharging = true,
                manualRunActive = false,
            ),
        )
    }

    @Test
    fun `the charging constraint disabled leaves downloads alone`() {
        assertEquals(
            Action.CONTINUE,
            NetworkPolicy.chargingAction(
                charging = false,
                requiresCharging = false,
                manualRunActive = false,
            ),
        )
    }

    @Test
    fun `back on the charger resumes downloads`() {
        assertEquals(
            Action.RESUME_DOWNLOADS,
            NetworkPolicy.chargingAction(
                charging = true,
                requiresCharging = true,
                manualRunActive = false,
            ),
        )
    }
}
