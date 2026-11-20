package com.example.jellywrist.sync

/**
 * Budget planner verdict (REQ-008, PLAN.md §4.4).
 *
 * [verdict] carries the information the UI can use to display an explicit warning —
 * never a silent failure.
 */
enum class BudgetVerdict {
    /** Budget sufficient, everything was accepted. */
    OK,
    /**
     * Some candidates were refused for lack of space: the UI should show
     * "X tracks deferred — adjust the quota or free up space".
     */
    PARTIAL,
    /**
     * **No** candidate accepted: the quota or disk is full.
     * The UI must show an explicit warning (never a silent failure).
     */
    NO_CAPACITY,
}

/**
 * Capacity planner result (REQ-008).
 *
 * Pure function, no I/O, no clock: all parameters are passed as arguments.
 *
 * Rules:
 * - Respects **both** the user quota and the real disk space minus the reserve
 *   (an 8 GB watch may be full before reaching a 6 GiB quota — REQ-008).
 * - Accepts greedily in the given order and defers the rest.
 * - **Never** partially accepts a candidate: a track larger than the total budget
 *   is deferred, not accepted.
 * - Zero/negative headroom → nothing accepted, verdict [BudgetVerdict.NO_CAPACITY].
 * - Freeing space (lower [alreadyUsedBytes]) accepts previously deferred candidates.
 * - Order is preserved: the planner does not reorder (the playlists→albums sort
 *   is the caller's responsibility, PLAN.md §4.2 step 4).
 *
 * @param candidates candidate tracks for download, in the caller's desired order
 * @param quotaBytes user quota configured in settings (SyncSettings.quotaBytes)
 * @param alreadyUsedBytes bytes already occupied by the Media3 cache (TrackDownloader.usedBytes)
 * @param availableDiskBytes bytes actually available on the volume (DeviceStorage)
 * @param reserveBytes safety reserve to keep (default 512 MiB)
 */
data class BudgetPlan(
    /** Accepted candidates, in entry order. */
    val accepted: List<Candidate>,
    /** Deferred candidates for lack of capacity, in entry order. */
    val deferred: List<Candidate>,
    /** Verdict for the UI: OK, PARTIAL or NO_CAPACITY. */
    val verdict: BudgetVerdict,
)

/**
 * Download candidate as seen by the budget planner.
 *
 * [trackId] and [sizeBytes] are sufficient; other metadata is handled by the diff.
 */
data class Candidate(
    val trackId: String,
    val sizeBytes: Long,
)

/** Default disk safety reserve (512 MiB). */
const val DEFAULT_RESERVE_BYTES = 512L * 1024L * 1024L

/**
 * Pure capacity planner (REQ-008, PLAN.md §8 task 8).
 *
 * @param candidates candidate tracks, in the caller's desired order
 * @param quotaBytes user quota (SyncSettings.quotaBytes, default ~6 GiB)
 * @param alreadyUsedBytes bytes already in cache (TrackDownloader.usedBytes)
 * @param availableDiskBytes free bytes on the volume (DeviceStorage.availableBytes)
 * @param reserveBytes safety reserve (default 512 MiB, CONFIGURABLE for tests)
 * @return [BudgetPlan] with accepted/deferred lists and the verdict
 */
fun plan(
    candidates: List<Candidate>,
    quotaBytes: Long,
    alreadyUsedBytes: Long,
    availableDiskBytes: Long,
    reserveBytes: Long = DEFAULT_RESERVE_BYTES,
): BudgetPlan {
    // Remaining headroom under the user quota
    val quotaRemaining = quotaBytes - alreadyUsedBytes
    // Real disk headroom after the safety reserve
    val diskRemaining = availableDiskBytes - reserveBytes

    // Both constraints must be respected: the more restrictive one wins.
    val headroom = minOf(quotaRemaining, diskRemaining)

    if (headroom <= 0L || candidates.isEmpty()) {
        val verdict = when {
            candidates.isEmpty() -> BudgetVerdict.OK
            else -> BudgetVerdict.NO_CAPACITY
        }
        return BudgetPlan(
            accepted = emptyList(),
            deferred = candidates,
            verdict = verdict,
        )
    }

    val accepted = mutableListOf<Candidate>()
    val deferred = mutableListOf<Candidate>()
    var remaining = headroom

    for (candidate in candidates) {
        if (candidate.sizeBytes <= remaining) {
            accepted.add(candidate)
            remaining -= candidate.sizeBytes
        } else {
            // A candidate larger than the total budget is deferred, never accepted
            deferred.add(candidate)
        }
    }

    val verdict = when {
        deferred.isEmpty() -> BudgetVerdict.OK
        accepted.isEmpty() -> BudgetVerdict.NO_CAPACITY
        else -> BudgetVerdict.PARTIAL
    }

    return BudgetPlan(
        accepted = accepted,
        deferred = deferred,
        verdict = verdict,
    )
}
