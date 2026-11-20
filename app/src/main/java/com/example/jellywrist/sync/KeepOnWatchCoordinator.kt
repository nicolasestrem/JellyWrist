package com.example.jellywrist.sync

import com.example.jellywrist.util.SyncTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** A condition the user must agree to before a manual sync consumes anything. */
enum class ConsentPrompt {
    /** Below the battery threshold: a multi-gigabyte pass could flatten the watch (REQ-007). */
    Battery,

    /** The active network is metered — on Wear OS usually the phone's Bluetooth proxy. */
    Metered,
}

/**
 * The conditions a manual sync must respect, as a port.
 *
 * Exists so the consent ladder is JVM-testable: the production implementation is
 * `NetworkAwareness`, which needs Horologist's network stack and an Android `Context`.
 */
interface SyncPreconditions {

    /** `true` when the active network is unmetered (Wi-Fi). */
    fun isUnmetered(): Boolean

    /** `true` when the battery is low enough that the user should be warned first. */
    fun shouldWarnBeforeManualSync(): Boolean
}

/**
 * The single path from "the user asked for this content" to a running sync.
 *
 * Three call sites wanted it and each did something different: the random-mix picker and the
 * downloads screen each spelled the battery-then-network ladder out by hand, while pinning a
 * collection skipped it and called [SyncControl.scheduleAutomaticOneShot] instead. That last
 * one is the reason "Sync to watch" looked broken: an automatic pass carries the REQ-007
 * defaults, charger **and** unmetered Wi-Fi, so the request sat in WorkManager until the
 * watch was next docked — with nothing on screen to say so.
 *
 * A manual pass lifts the charging constraint and asks about anything it would spend. This
 * class is deliberately **not** a singleton: the prompt belongs to the screen that raised it.
 */
class KeepOnWatchCoordinator @Inject constructor(
    private val syncControl: SyncControl,
    private val preconditions: SyncPreconditions,
) {

    private val _prompt = MutableStateFlow<ConsentPrompt?>(null)

    /** The condition currently awaiting an answer, or `null` when nothing is pending. */
    val prompt: StateFlow<ConsentPrompt?> = _prompt.asStateFlow()

    private var allowMetered = false
    private var allowLowBattery = false

    /**
     * Requests a sync now, raising a [prompt] for the first condition that needs consent.
     *
     * Call [confirm] to grant the pending prompt and continue, or [dismiss] to abandon.
     */
    suspend fun request() {
        allowMetered = false
        allowLowBattery = false
        advance()
    }

    /** The user agreed to the pending prompt; carry on to the next condition or to the sync. */
    suspend fun confirm() {
        when (_prompt.value) {
            ConsentPrompt.Battery -> allowLowBattery = true
            ConsentPrompt.Metered -> allowMetered = true
            null -> return
        }
        _prompt.value = null
        advance()
    }

    /** Consent refused: nothing is sent and nothing is scheduled. */
    fun dismiss() {
        _prompt.value = null
    }

    private suspend fun advance() {
        if (!allowLowBattery && preconditions.shouldWarnBeforeManualSync()) {
            _prompt.value = ConsentPrompt.Battery
            return
        }
        if (!allowMetered && !preconditions.isUnmetered()) {
            _prompt.value = ConsentPrompt.Metered
            return
        }
        _prompt.value = null
        SyncTrace.i("keepOnWatch requesting sync allowMetered=$allowMetered allowLowBattery=$allowLowBattery")
        syncControl.syncNow(allowMetered = allowMetered, allowLowBattery = allowLowBattery)
    }
}
