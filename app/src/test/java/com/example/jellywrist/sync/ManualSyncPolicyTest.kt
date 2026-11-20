package com.example.jellywrist.sync

import androidx.work.NetworkType
import com.example.jellywrist.data.db.SyncOutcome
import com.example.jellywrist.data.settings.SyncSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSyncPolicyTest {

    @Test
    fun `every authorized manual pass resumes Media3 before sync`() = runTest {
        val cases = listOf(
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
        )

        cases.forEach { (granted, unmetered, allowMetered) ->
            val events = mutableListOf<String>()
            val result = runManualSync(
                batteryWarningRequired = false,
                highBandwidthGranted = granted,
                unmeteredAlready = unmetered,
                allowMetered = allowMetered,
                allowLowBattery = false,
                onBatteryConsentRequired = { events += "battery" },
                onConsentRequired = { events += "consent" },
                resumeDownloads = { events += "resume" },
                executeSync = { events += "sync"; SyncOutcome.SUCCESS },
            )

            assertEquals(SyncOutcome.SUCCESS, result)
            assertEquals(listOf("resume", "sync"), events)
        }
    }

    @Test
    fun `metered manual pass stops successfully until consent`() = runTest {
        var consentRequired = false
        var resumed = false
        var executed = false

        val result = runManualSync(
            batteryWarningRequired = false,
            highBandwidthGranted = false,
            unmeteredAlready = false,
            allowMetered = false,
            allowLowBattery = false,
            onBatteryConsentRequired = { error("battery consent should not be requested") },
            onConsentRequired = { consentRequired = true },
            resumeDownloads = { resumed = true },
            executeSync = { executed = true; SyncOutcome.SUCCESS },
        )

        assertNull(result)
        assertTrue(consentRequired)
        assertFalse(resumed)
        assertFalse(executed)
    }

    @Test
    fun `low battery manual pass stops before resuming downloads until consent`() = runTest {
        val events = mutableListOf<String>()

        val result = runManualSync(
            batteryWarningRequired = true,
            highBandwidthGranted = true,
            unmeteredAlready = true,
            allowMetered = false,
            allowLowBattery = false,
            onBatteryConsentRequired = { events += "battery" },
            onConsentRequired = { events += "metered" },
            resumeDownloads = { events += "resume" },
            executeSync = { events += "sync"; SyncOutcome.SUCCESS },
        )

        assertNull(result)
        assertEquals(listOf("battery"), events)
    }

    @Test
    fun `explicit low battery consent authorizes queue resumption`() = runTest {
        val events = mutableListOf<String>()

        val result = runManualSync(
            batteryWarningRequired = true,
            highBandwidthGranted = true,
            unmeteredAlready = true,
            allowMetered = false,
            allowLowBattery = true,
            onBatteryConsentRequired = { events += "battery" },
            onConsentRequired = { events += "metered" },
            resumeDownloads = { events += "resume" },
            executeSync = { events += "sync"; SyncOutcome.SUCCESS },
        )

        assertEquals(SyncOutcome.SUCCESS, result)
        assertEquals(listOf("resume", "sync"), events)
    }

    @Test
    fun `automatic constraints retain charging and unmetered policy`() {
        val constraints = automaticSyncConstraints(
            SyncSettings(
                quotaBytes = 1,
                requiresCharging = true,
                unmeteredOnly = true,
                periodicityHours = 24,
                pinnedCertificateSha256 = null,
            ),
        )

        assertTrue(constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
    }

    // ── Worker result mapping ───────────────────────────────────────────────

    @Test
    fun `a failed manual run is reported instead of silently retried`() {
        // A manual pass that fails used to return Result.retry(), which hands it to
        // WorkManager's one-hour exponential backoff. The user, who had just tapped sync,
        // saw nothing happen and no reason why.
        assertEquals(WorkOutcome.FAILURE, workOutcomeFor(SyncOutcome.FAILED, manual = true))
    }

    @Test
    fun `a failed automatic run is retried with backoff`() {
        assertEquals(WorkOutcome.RETRY, workOutcomeFor(SyncOutcome.FAILED, manual = false))
    }

    @Test
    fun `a pass awaiting consent is not a failure`() {
        assertEquals(WorkOutcome.SUCCESS, workOutcomeFor(null, manual = true))
    }

    @Test
    fun `a partial pass succeeds`() {
        assertEquals(WorkOutcome.SUCCESS, workOutcomeFor(SyncOutcome.PARTIAL, manual = true))
    }

    @Test
    fun `an interrupted pass succeeds because it will resume`() {
        assertEquals(WorkOutcome.SUCCESS, workOutcomeFor(SyncOutcome.PAUSED, manual = false))
    }
}
