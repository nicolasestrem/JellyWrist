package com.example.jellywrist.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for [KeepOnWatchCoordinator] — the one consent ladder in front of a manual sync.
 *
 * The ladder itself already existed, written out twice by hand in two ViewModels, and a
 * third caller (pinning a collection) skipped it entirely and scheduled an *automatic* pass
 * instead: constrained to charger plus Wi-Fi, so tapping "keep on watch" appeared to do
 * nothing at all until the watch was next docked. REQ-007 keeps those constraints for the
 * overnight cycle and lifts them for a run the user asked for, which is what this encodes.
 */
class KeepOnWatchCoordinatorTest {

    private lateinit var sync: RecordingSyncControl
    private lateinit var preconditions: FakePreconditions
    private lateinit var coordinator: KeepOnWatchCoordinator

    @Before
    fun setUp() {
        sync = RecordingSyncControl()
        preconditions = FakePreconditions()
        coordinator = KeepOnWatchCoordinator(sync, preconditions)
    }

    @Test
    fun `on Wi-Fi with a healthy battery the sync starts immediately`() = runTest {
        coordinator.request()

        assertEquals(listOf(false to false), sync.syncNowCalls)
        assertNull(coordinator.prompt.value)
    }

    @Test
    fun `a manual sync never schedules the constrained automatic pass`() = runTest {
        coordinator.request()

        assertEquals(
            "pinning must not wait for a charger and Wi-Fi",
            0,
            sync.automaticOneShotCalls,
        )
    }

    @Test
    fun `a low battery is asked about before anything is sent`() = runTest {
        preconditions.warnBeforeManualSync = true

        coordinator.request()

        assertEquals(ConsentPrompt.Battery, coordinator.prompt.value)
        assertEquals(emptyList<Pair<Boolean, Boolean>>(), sync.syncNowCalls)
    }

    @Test
    fun `a metered network is asked about before anything is sent`() = runTest {
        preconditions.unmetered = false

        coordinator.request()

        assertEquals(ConsentPrompt.Metered, coordinator.prompt.value)
        assertEquals(emptyList<Pair<Boolean, Boolean>>(), sync.syncNowCalls)
    }

    @Test
    fun `both consents are carried into the sync request`() = runTest {
        preconditions.warnBeforeManualSync = true
        preconditions.unmetered = false

        coordinator.request()
        assertEquals(ConsentPrompt.Battery, coordinator.prompt.value)

        coordinator.confirm()
        assertEquals("the second condition is asked separately", ConsentPrompt.Metered, coordinator.prompt.value)
        assertEquals(emptyList<Pair<Boolean, Boolean>>(), sync.syncNowCalls)

        coordinator.confirm()
        assertEquals(listOf(true to true), sync.syncNowCalls)
        assertNull(coordinator.prompt.value)
    }

    @Test
    fun `dismissing a prompt sends nothing`() = runTest {
        preconditions.unmetered = false
        coordinator.request()

        coordinator.dismiss()

        assertNull(coordinator.prompt.value)
        assertEquals(emptyList<Pair<Boolean, Boolean>>(), sync.syncNowCalls)
    }

    private class RecordingSyncControl : SyncControl {
        val syncNowCalls = mutableListOf<Pair<Boolean, Boolean>>()
        var automaticOneShotCalls = 0

        override suspend fun syncNow(allowMetered: Boolean, allowLowBattery: Boolean) {
            syncNowCalls.add(allowMetered to allowLowBattery)
        }

        override suspend fun scheduleAutomaticOneShot() {
            automaticOneShotCalls++
        }
    }

    private class FakePreconditions : SyncPreconditions {
        var unmetered = true
        var warnBeforeManualSync = false

        override fun isUnmetered(): Boolean = unmetered

        override fun shouldWarnBeforeManualSync(): Boolean = warnBeforeManualSync
    }
}
