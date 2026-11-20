package com.example.jellywrist.sync

import com.example.jellywrist.data.db.TrackState

/**
 * Local data needed for the diff computation (REQ-006, PLAN.md §4.2 step 2).
 *
 * Built from Room by the [SyncEngine]: these are immutable projections, not direct Room
 * entities — the diff must stay pure and I/O-free.
 */
data class LocalState(
    /** Tracks currently stored in the database, indexed by Jellyfin id. */
    val tracks: Map<String, LocalTrack>,
    /** Tracks belonging to each pinned collection (implicit refcount). */
    val collectionTracks: Map<String, Set<String>>,
    /** Set of ids of currently pinned collections. */
    val pinnedCollectionIds: Set<String>,
) {
    /** How many pinned collections contain this track? */
    fun refCount(trackId: String): Int =
        collectionTracks.values.count { trackId in it }
}

/** Local view of a track — only the fields relevant to the diff. */
data class LocalTrack(
    val id: String,
    val state: TrackState,
    val sizeBytes: Long,
    val codec: String?,
)

/**
 * Server state of a pinned collection (PLAN.md §4.2 step 1).
 *
 * [trackIds] is the ordered set of track ids on the server side; [tracks] carries the
 * metadata needed by the budget planner and by the non-decodable codec detection
 * (DEC-004).
 */
data class ServerCollectionState(
    val collectionId: String,
    val trackIds: List<String>,
    val tracks: Map<String, ServerTrack>,
)

/** Server view of a track for the diff. */
data class ServerTrack(
    val id: String,
    val sizeBytes: Long,
    val codec: String?,
)

/**
 * Budget planner verdict, injected into the diff to separate accepted tracks from
 * deferred ones (REQ-008).
 * [BudgetCapacity.freeBytes] is the number of available bytes *after* subtracting the
 * already-consumed quota and the safety reserve. The diff uses it to know whether it
 * can still accept tracks.
 */
data class BudgetCapacity(
    val freeBytes: Long,
    val quotaBytes: Long,
    val alreadyUsedBytes: Long,
)

/**
 * Pure diff result (REQ-006, PLAN.md §8 task 11).
 *
 * Completely side-effect-free: no clock, no I/O, no randomness.
 * The sync engine consumes this result for the impure operations.
 */
data class SyncDecision(
    /** Tracks to enqueue in the [TrackDownloader] (new or errored). */
    val toEnqueue: List<String>,
    /** Orphan tracks to remove from the downloader and delete from Room. */
    val toRemove: List<String>,
    /** Tracks deferred for lack of budget — will be marked PENDING_BUDGET. */
    val toDeferForBudget: List<String>,
    /**
     * Tracks in non-decodable codecs — flagged but **still enqueued**
     * (DEC-003 / DEC-004). The downloader will mark them [TrackState.UNSUPPORTED]
     * after download.
     */
    val unsupported: List<String>,
    /**
     * Per-collection recon watermark (needed for artists whose album list
     * evolves server-side).
     */
    val reconWatermark: Map<String, String?>,
)

/**
 * Set of codecs natively decodable by ExoPlayer (DEC-004).
 *
 * Any codec absent from this set will result in [TrackState.UNSUPPORTED] after
 * download, but **will still be downloaded** (DEC-003).
 */
val DECODABLE_CODECS: Set<String> = setOf(
    "flac", "mp3", "aac", "m4a", "vorbis", "opus", "wav", "pcm",
)

/** True if the codec is a known alias of AAC in Jellyfin metadata. */
private fun isAacAlias(codec: String?): Boolean = codec == "m4a"

/** True if ExoPlayer can decode this codec. */
internal fun isDecodable(codec: String?): Boolean =
    when {
        codec == null -> true // missing info → give it a shot
        codec in DECODABLE_CODECS -> true
        isAacAlias(codec) -> true
        else -> false
    }

/**
 * Pure server ↔ local diff (REQ-006).
 *
 * Rules:
 * - track added server-side → [SyncDecision.toEnqueue]
 * - track removed server-side, not belonging to any other pinned collection → [SyncDecision.toRemove]
 *   (refcount via [LocalState.collectionTracks])
 * - track already [TrackState.READY] → no re-enqueue (idempotence)
 * - track in [TrackState.ERROR] → re-enqueue (retry, backoff is handled by the caller)
 * - non-decodable codec → flagged in [SyncDecision.unsupported] **and enqueued** (DEC-003)
 * - tracks beyond the budget → [SyncDecision.toDeferForBudget]
 * - track [TrackState.PENDING_BUDGET] → promoted to [SyncDecision.toEnqueue] if budget allows
 * - empty server state for a pinned collection → purge that collection without touching others
 *
 * @param local projected Room state (I/O-free)
 * @param server server state of pinned collections
 * @param budget capacity snapshot from the planner
 * @return [SyncDecision] consumable by the [SyncEngine]
 */
fun diff(
    local: LocalState,
    server: List<ServerCollectionState>,
    budget: BudgetCapacity,
): SyncDecision {
    val toEnqueue = mutableListOf<String>()
    val toRemove = mutableListOf<String>()
    val toDeferForBudget = mutableListOf<String>()
    val unsupported = mutableListOf<String>()
    val reconWatermark = mutableMapOf<String, String?>()
    var remainingBudget = budget.freeBytes

    // Set of all tracks still present server-side in pinned collections
    val serverTrackIds = mutableSetOf<String>()
    // All collection→track links post-recon
    val newCollectionTracks = mutableMapOf<String, Set<String>>()

    for (coll in server) {
        newCollectionTracks[coll.collectionId] = coll.trackIds.toSet()
        serverTrackIds.addAll(coll.trackIds)

        // Watermark: last track id serving as cursor for artist pagination
        reconWatermark[coll.collectionId] = coll.trackIds.lastOrNull()
    }

    // Orphans: local tracks that no longer belong to any pinned collection
    // after applying the new server state. We use the refcount *after* recon.
    val futureRefCount = mutableMapOf<String, Int>()
    for ((collId, trackIds) in newCollectionTracks) {
        for (trackId in trackIds) {
            futureRefCount[trackId] = (futureRefCount[trackId] ?: 0) + 1
        }
    }

    // Local tracks to examine
    val allLocalTrackIds = local.tracks.keys

    for (trackId in allLocalTrackIds) {
        val localTrack = local.tracks[trackId] ?: continue
        val isStillReferenced = futureRefCount.getOrDefault(trackId, 0) > 0

        if (!isStillReferenced) {
            // Orphan: only remove if the current refcount is also 0 (or if we just
            // lost the last reference). Here, futureRefCount == 0 after recon.
            toRemove.add(trackId)
        }
    }

    // Tracks to enqueue: present server-side but absent or errored locally.
    // A track shared across multiple collections (playlist + album) must not
    // consume the budget twice (bot review bug #6, round 2).
    val seenBudgetIds = mutableSetOf<String>()

    for (coll in server) {
        for (trackId in coll.trackIds) {
            val serverTrack = coll.tracks[trackId]
            val localTrack = local.tracks[trackId]

            // Do not enqueue what is already READY (idempotence)
            if (localTrack?.state == TrackState.READY) continue

            // Non-decodable codec: flag AND enqueue (DEC-003)
            val codec = serverTrack?.codec ?: localTrack?.codec
            if (!isDecodable(codec)) {
                unsupported.add(trackId)
                // Also falls into toEnqueue if not already READY (handled above)
            }

            // Size for budget calculation
            val sizeBytes = serverTrack?.sizeBytes ?: localTrack?.sizeBytes ?: 0L

            // Determine whether the track can be accepted into the budget
            val state = localTrack?.state
            val isPendingBudget = state == TrackState.PENDING_BUDGET
            val isError = state == TrackState.ERROR
            val isToDownload = state == TrackState.TO_DOWNLOAD || state == null

            when {
                isPendingBudget || isError || isToDownload -> {
                    if (seenBudgetIds.add(trackId)) {
                        if (sizeBytes <= remainingBudget) {
                            toEnqueue.add(trackId)
                            remainingBudget -= sizeBytes
                        } else {
                            toDeferForBudget.add(trackId)
                        }
                    }
                }
                // DOWNLOADING: do not touch, but reserve the remaining bytes in
                // the budget — otherwise the quota would be exceeded when they finish
                // (bot review bug #11, round 2).
                state == TrackState.DOWNLOADING -> {
                    if (seenBudgetIds.add(trackId)) {
                        // Reserve the full size — conservative but correct
                        // (bot review bug #11, round 2)
                        remainingBudget -= sizeBytes
                    }
                }
                // READY or UNSUPPORTED: already handled or enqueued
                state == TrackState.UNSUPPORTED -> { /* do not re-enqueue */ }
            }
        }
    }

    return SyncDecision(
        toEnqueue = toEnqueue.distinct(),
        toRemove = toRemove.distinct(),
        toDeferForBudget = toDeferForBudget.distinct(),
        unsupported = unsupported.distinct(),
        reconWatermark = reconWatermark,
    )
}
