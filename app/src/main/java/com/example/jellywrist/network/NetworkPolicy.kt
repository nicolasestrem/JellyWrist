package com.example.jellywrist.network

/**
 * Network policy for the download engine (PLAN.md §8 task 12) — pure function.
 *
 * Wear OS favors the phone's Bluetooth proxy (~125 KB/s) and considers that network
 * as **metered**; Wi-Fi is turned on only on demand. The policy translates each
 * observed transition into an action on the download queue:
 *
 * - Wi-Fi → metered during active sync: pause + "paused — will resume" notification
 *   (DEC-005: never presented as a failure);
 * - metered → Wi-Fi: resume;
 * - manual sync on a metered network: allowed but **never silent** — the UI must
 *   warn before consuming mobile/proxy data.
 */
object NetworkPolicy {

    enum class Action {
        /** Nothing to do: the network situation suits the current activity. */
        CONTINUE,

        /** Suspend downloads without losing state (Range-based resume). */
        PAUSE_DOWNLOADS,

        /** Resume suspended downloads. */
        RESUME_DOWNLOADS,

        /** Metered network + manual request: warn before consuming data. */
        WARN_METERED,
    }

    /**
     * Decides the action from a network transition.
     *
     * @param wasUnmetered network state before the transition
     * @param isUnmetered network state after the transition
     * @param syncRunning a sync or downloads are active
     * @param manualRequested the user just requested a manual sync
     */
    fun decide(
        wasUnmetered: Boolean,
        isUnmetered: Boolean,
        syncRunning: Boolean,
        manualRequested: Boolean,
    ): Action = when {
        // Wi-Fi loss during active sync: pause rather than consume the BT proxy.
        wasUnmetered && !isUnmetered && syncRunning -> Action.PAUSE_DOWNLOADS

        // Wi-Fi back: resume where we left off.
        !wasUnmetered && isUnmetered && syncRunning -> Action.RESUME_DOWNLOADS

        // Manual request on a metered network: allowed, but the user must consent.
        manualRequested && !isUnmetered -> Action.WARN_METERED

        else -> Action.CONTINUE
    }

    /**
     * Whether a manual run's ownership of the queue ends the moment its pass returns.
     *
     * Only when the pass left nothing to download. `SyncEngine.syncPass` returns once the
     * tracks are *registered*, not once the bytes have landed, so a run that enqueued work is
     * still running long after the worker finishes. Clearing the override there would hand the
     * queue straight back to the automatic constraints, and the charging observer would pause
     * the very download the user authorised off the charger — REQ-007's manual-sync contract
     * inverted. Such a run is released by the drain-based clearing instead, when the progress
     * flow reports an empty queue.
     *
     * The empty case is the one the drain can never handle: with nothing enqueued, no progress
     * emission ever arrives and the override would outlive the run indefinitely.
     */
    fun manualRunEndsWithPass(hasOutstandingWork: Boolean): Boolean = !hasOutstandingWork

    /**
     * Decides what the charging constraint should do to the queue.
     *
     * [manualRunActive] is the reason this is a function rather than an `if`. The charging
     * observer re-evaluates on every `ACTION_BATTERY_CHANGED` broadcast, which the system
     * sends constantly, so a watch off its charger used to re-issue a pause over and over —
     * including seconds after the sync worker had deliberately resumed the queue for a
     * manual pass the user had authorised. REQ-007 lifts the charging constraint for manual
     * sync precisely so it can run on battery; honouring it there cancelled the user's own
     * request.
     */
    fun chargingAction(
        charging: Boolean,
        requiresCharging: Boolean,
        manualRunActive: Boolean,
    ): Action = when {
        !requiresCharging -> Action.CONTINUE
        manualRunActive -> Action.CONTINUE
        charging -> Action.RESUME_DOWNLOADS
        else -> Action.PAUSE_DOWNLOADS
    }
}
