package com.example.jellywrist.sync

import com.example.jellywrist.data.db.CollectionDao
import com.example.jellywrist.data.db.CollectionType
import com.example.jellywrist.data.db.CollectionTrackDao
import com.example.jellywrist.data.db.CollectionTrackEntity
import com.example.jellywrist.data.db.SyncLogDao
import com.example.jellywrist.data.db.SyncLogEntity
import com.example.jellywrist.data.db.SyncOutcome
import com.example.jellywrist.data.db.TrackDao
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.data.settings.SettingsRepository
import com.example.jellywrist.data.storage.DeviceStorage
import com.example.jellywrist.download.TrackDownloader
import com.example.jellywrist.util.SyncTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global sync state, consumable by the UI and the scheduler.
 *
 * [PAUSED] exists so that the Android 15+ FGS 6-hour cap is never presented as a failure:
 * the UI should say "paused — will resume on the next cycle" (DEC-005).
 */
enum class SyncState {
    /** No sync in progress. */
    IDLE,
    /** A one-shot sync is enqueued and waiting to start. */
    QUEUED,
    /** A sync pass is active (foreground service + WorkManager). */
    SYNCING,
    /** Sync interrupted by the 6-hour cap or sleep — will resume automatically. */
    PAUSED,
    /** The last pass failed (network, auth, disk full). */
    FAILED,
}

/**
 * Jellyfin metadata-fetching port for the sync engine.
 *
 * Separates the sync engine from the Jellyfin SDK for two reasons:
 * 1. JVM testability — the SDK requires an Android Context.
 * 2. Single responsibility — the engine decides what to sync, this port knows how to ask.
 *
 * Exposed as an interface so `LibraryBrowse` can provide its real implementation, while
 * tests provide a fake.
 *
 * REQ-002: all Jellyfin JSON access goes through the typed SDK (ItemsApi, UserLibraryApi…).
 */
interface SyncMetadataSource {

    /**
     * Fetches the tracks of a pinned collection, in server order.
     *
     * [type] discriminates the fetch: a playlist is read via `PlaylistsApi` (playlist
     * order), an album via its audio children, an artist via the `artistIds` filter —
     * a naive `parentId` would be wrong for the latter two.
     */
    suspend fun fetchCollectionTracks(
        collectionId: String,
        type: CollectionType,
        accessToken: String,
        baseUrl: String,
    ): List<SyncTrackMeta>
}

/** Track metadata returned by the fetching port. */
data class SyncTrackMeta(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val durationMs: Long = 0L,
    val codec: String? = null,
    val container: String? = null,
    val sizeBytes: Long = 0L,
    val indexNumber: Int? = null,
    val imageTag: String? = null,
)

/**
 * Sync engine (REQ-006, REQ-008, PLAN.md §4.2 / §8 task 9).
 *
 * Orchestrates a complete sync pass:
 * 1. Load local state from Room
 * 2. Fetch server state of pinned collections via [SyncMetadataSource]
 * 3. Compute the diff via [diff] (pure)
 * 4. Apply the budget via [plan] (pure)
 * 5. Execute impure operations: enqueue, remove, state updates
 * 6. Log the pass in [SyncLogEntity]
 *
 * Is **hardened** against the FGS 6-hour cap (DEC-005): if the service is killed, WorkManager
 * will launch a new pass and the diff resumes from the existing Room state. A truncated
 * pass ends in [SyncOutcome.PAUSED], never in FAILED.
 *
 * Does not implement REQ-010 ([DEFERRED]) — no PlaybackReport or status upload.
 *
 * @property downloader download port (Media3 cache, Range-resumable)
 * @property deviceStorage real disk space
 * @property settingsRepository user settings (quota, constraints)
 * @property metadataSource Jellyfin fetching port (ItemsApi via SDK)
 * @property collectionDao Room collections access
 * @property trackDao Room tracks access
 * @property collectionTrackDao Room collection↔track links
 * @property syncLogDao sync log
 * @property backend Jellyfin port (to create an ApiClient)
 */
@Singleton
class SyncEngine @Inject constructor(
    private val downloader: TrackDownloader,
    private val deviceStorage: DeviceStorage,
    private val settingsRepository: SettingsRepository,
    private val metadataSource: SyncMetadataSource,
    private val collectionDao: CollectionDao,
    private val trackDao: TrackDao,
    private val collectionTrackDao: CollectionTrackDao,
    private val syncLogDao: SyncLogDao,
) {

    /**
     * Executes a complete sync pass.
     *
     * @param session authenticated Jellyfin session
     * @param wasPaused true if this pass resumes after an FGS 6-hour interruption
     * @return [SyncOutcome] of the pass (SUCCESS, PARTIAL, PAUSED, FAILED)
     */
    suspend fun syncPass(
        session: com.example.jellywrist.data.auth.AuthSession,
        wasPaused: Boolean = false,
    ): SyncOutcome {
        val startTime = System.currentTimeMillis()
        var added = 0
        var removed = 0
        var failed = 0
        var firstFailureMessage: String? = null
        var deferredForBudget = 0

        val logEntry = SyncLogEntity(
            startedAtEpochMs = startTime,
            outcome = SyncOutcome.PAUSED, // default — overridden if we terminate normally
        )

        return try {
            val pinnedCollections = collectionDao.pinned()
            SyncTrace.i("pass start pinnedCollections=${pinnedCollections.size} wasPaused=$wasPaused")
            if (pinnedCollections.isEmpty()) {
                syncLogDao.upsert(logEntry.copy(
                    finishedAtEpochMs = System.currentTimeMillis(),
                    outcome = SyncOutcome.SUCCESS,
                    message = "No pinned collection",
                ))
                return SyncOutcome.SUCCESS
            }

            // ── 1. Load local state ───────────────────────────────────────────
            val localTracks = mutableMapOf<String, LocalTrack>()
            val localCollectionTracks = mutableMapOf<String, MutableSet<String>>()

            for (coll in pinnedCollections) {
                val trackIds = collectionTrackDao.trackIds(coll.id)
                localCollectionTracks[coll.id] = trackIds.toMutableSet()
                val entities = trackDao.byIds(trackIds)
                for (entity in entities) {
                    localTracks[entity.id] = LocalTrack(
                        id = entity.id,
                        state = entity.state,
                        sizeBytes = entity.sizeBytes,
                        codec = entity.codec,
                    )
                }
            }

            val local = LocalState(
                tracks = localTracks,
                collectionTracks = localCollectionTracks,
                pinnedCollectionIds = pinnedCollections.map { it.id }.toSet(),
            )
            // ── 2. Fetch server state ─────────────────────────────────────────
            val serverStates = mutableListOf<ServerCollectionState>()
            for (coll in pinnedCollections) {
                if (coll.localOnly) {
                    // Local collection ("500 random tracks"): no server counterpart.
                    // Recon **identity** — the target state is its own local content:
                    // missing tracks are enqueued, nothing is purged, and the collection
                    // counts toward refcount like any other pin.
                    val ids = localCollectionTracks[coll.id].orEmpty().toList()
                    val tracksById = trackDao.byIds(ids).associate { entity ->
                        entity.id to ServerTrack(
                            id = entity.id,
                            sizeBytes = entity.sizeBytes,
                            codec = entity.codec,
                        )
                    }
                    serverStates.add(
                        ServerCollectionState(
                            collectionId = coll.id,
                            trackIds = ids,
                            tracks = tracksById,
                        ),
                    )
                    continue
                }

                val serverTracks = try {
                    metadataSource.fetchCollectionTracks(
                        collectionId = coll.id,
                        type = coll.type,
                        accessToken = session.accessToken,
                        baseUrl = session.baseUrl,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One collection we cannot read must not condemn the pass. There was no
                    // per-collection guard here: a single unreachable or server-deleted
                    // playlist threw, the outer catch logged FAILED, and **nothing** was
                    // enqueued for any other pin — the watch simply stopped downloading.
                    // The collection is reconciled by identity instead (its own local
                    // content), so its tracks are neither purged nor forgotten, and the pass
                    // continues with the pins that did answer.
                    SyncTrace.w("fetch failed for collection=${coll.id}", e)
                    failed++
                    if (firstFailureMessage == null) firstFailureMessage = e.message?.take(200)
                    val knownIds = localCollectionTracks[coll.id].orEmpty().toList()
                    serverStates.add(
                        ServerCollectionState(
                            collectionId = coll.id,
                            trackIds = knownIds,
                            tracks = trackDao.byIds(knownIds).associate { entity ->
                                entity.id to ServerTrack(
                                    id = entity.id,
                                    sizeBytes = entity.sizeBytes,
                                    codec = entity.codec,
                                )
                            },
                        ),
                    )
                    continue
                }
                SyncTrace.i(
                    "fetched collection=${coll.id} type=${coll.type} serverTracks=${serverTracks.size}",
                )
                val serverTracksMap = serverTracks.associate { meta ->
                    meta.id to ServerTrack(
                        id = meta.id,
                        sizeBytes = meta.sizeBytes,
                        codec = meta.codec,
                    )
                }
                serverStates.add(
                    ServerCollectionState(
                        collectionId = coll.id,
                        trackIds = serverTracks.map { it.id },
                        tracks = serverTracksMap,
                    ),
                )

                // Upsert server metadata into Room (existing states are preserved)
                val trackEntities = serverTracks.map { meta ->
                    val existingState = localTracks[meta.id]?.state
                    TrackEntity(
                        id = meta.id,
                        title = meta.title,
                        artist = meta.artist,
                        album = meta.album,
                        albumId = meta.albumId,
                        durationMs = meta.durationMs,
                        codec = meta.codec,
                        container = meta.container,
                        sizeBytes = meta.sizeBytes,
                        state = existingState ?: TrackState.TO_DOWNLOAD,
                        indexNumber = meta.indexNumber,
                        imageTag = meta.imageTag,
                    )
                }
                if (trackEntities.isNotEmpty()) {
                    trackDao.upsertAll(trackEntities)
                }

                // Update collection→track links
                val links = serverTracks.mapIndexed { index, meta ->
                    CollectionTrackEntity(
                        collectionId = coll.id,
                        trackId = meta.id,
                        sortIndex = index,
                    )
                }
                collectionTrackDao.replaceContent(coll.id, links)
            }

            // ── 3. Compute the budget ──────────────────────────────────────────
            val settings = settingsRepository.settings.first()
            val usedBytes = downloader.usedBytes()
            val availableDisk = deviceStorage.availableBytes()
            val quotaRemaining = settings.quotaBytes - usedBytes
            val diskRemaining = availableDisk - DEFAULT_RESERVE_BYTES
            val headroom = minOf(quotaRemaining, diskRemaining)

            val budgetCapacity = BudgetCapacity(
                freeBytes = headroom,
                quotaBytes = settings.quotaBytes,
                alreadyUsedBytes = usedBytes,
            )

            SyncTrace.i(
                "budget headroom=$headroom quota=${settings.quotaBytes} used=$usedBytes " +
                    "availableDisk=$availableDisk",
            )

            // ── 4. Compute the diff ────────────────────────────────────────────
            val decision = diff(local, serverStates, budgetCapacity)
            SyncTrace.i(
                "diff toEnqueue=${decision.toEnqueue.size} toRemove=${decision.toRemove.size} " +
                    "deferForBudget=${decision.toDeferForBudget.size} " +
                    "unsupported=${decision.unsupported.size}",
            )

            // ── 5. Purge orphans ──────────────────────────────────────────────
            // The refcounted purge (REQ-006) concerns **bytes**: the Media3 cache is
            // released, but the catalog rows and their links remain — offline browsing
            // of an unpinned collection must keep working (PLAN.md §8 task 5). Re-pinning
            // later is just a re-download.
            if (decision.toRemove.isNotEmpty()) {
                downloader.remove(decision.toRemove)
                for (trackId in decision.toRemove) {
                    trackDao.updateProgress(
                        id = trackId,
                        state = TrackState.TO_DOWNLOAD,
                        downloadedBytes = 0L,
                        error = null,
                    )
                }
                removed = decision.toRemove.size
            }

            // ── 5b. Database-level orphan sweep ───────────────────────────────
            // The pure diff only sees tracks still LINKED to a pinned collection.
            // A track whose links disappeared between two passes (random re-draw via
            // replaceContent, collection deleted server-side…) is invisible to it:
            // the SQL refcount catches it. Without this sweep, each re-draw would leave
            // its orphaned bytes in the cache until the quota is saturated
            // (REQ-006 / REQ-008).
            val dbOrphans = collectionTrackDao.orphanTrackIds()
                .filter { it !in decision.toRemove }
                .filter { trackId ->
                    val t = trackDao.byId(trackId)
                    t != null && (t.state != TrackState.TO_DOWNLOAD || t.downloadedBytes > 0L)
                }
            if (dbOrphans.isNotEmpty()) {
                downloader.remove(dbOrphans)
                for (trackId in dbOrphans) {
                    trackDao.updateProgress(
                        id = trackId,
                        state = TrackState.TO_DOWNLOAD,
                        downloadedBytes = 0L,
                        error = null,
                    )
                }
                removed += dbOrphans.size
            }

            // ── 6. Mark budget-deferred tracks ────────────────────────────────
            for (trackId in decision.toDeferForBudget) {
                trackDao.updateState(trackId, TrackState.PENDING_BUDGET)
            }
            deferredForBudget = decision.toDeferForBudget.size

            // ── 7. Enqueue accepted tracks (DEC-003) ──────────────────────────
            // decision.unsupported is purely informational for the UI (DEC-004):
            // the final UNSUPPORTED marking happens post-download by the downloader.
            // We enqueue ONLY decision.toEnqueue (which already includes eligible
            // non-decodable-codec tracks) and mark them all TO_DOWNLOAD.
            // NEVER pre-mark UNSUPPORTED before enqueueing: the downloader would skip
            // those tracks (Media3TrackDownloader does not re-enqueue UNSUPPORTED).
            if (decision.toEnqueue.isNotEmpty()) {
                for (trackId in decision.toEnqueue) {
                    trackDao.updateState(trackId, TrackState.TO_DOWNLOAD)
                }
                SyncTrace.i("enqueueing ${decision.toEnqueue.size} track(s) to the downloader")
                downloader.enqueue(decision.toEnqueue)
                added = decision.toEnqueue.size
            }

            // ── 8. Mark collections as synced ─────────────────────────────────
            val now = System.currentTimeMillis()
            for (coll in pinnedCollections) {
                collectionDao.markSynced(
                    id = coll.id,
                    epochMs = now,
                    watermark = decision.reconWatermark[coll.id],
                )
            }

            // ── 9. Log ────────────────────────────────────────────────────────
            val outcome = when {
                // Every pin failed: there is nothing honest to call this but a failure.
                failed > 0 && failed == pinnedCollections.size -> SyncOutcome.FAILED
                // Some pins failed or some tracks did not fit: partial, not failed — the
                // rest really did sync, and reporting FAILED would send a healthy pass into
                // WorkManager's retry backoff.
                failed > 0 || deferredForBudget > 0 -> SyncOutcome.PARTIAL
                else -> SyncOutcome.SUCCESS
            }
            syncLogDao.upsert(
                logEntry.copy(
                    finishedAtEpochMs = System.currentTimeMillis(),
                    added = added,
                    removed = removed,
                    failed = failed,
                    deferredForBudget = deferredForBudget,
                    outcome = outcome,
                    message = firstFailureMessage,
                ),
            )
            SyncTrace.i(
                "pass end outcome=$outcome added=$added removed=$removed failed=$failed " +
                    "deferredForBudget=$deferredForBudget",
            )

            outcome
        } catch (e: CancellationException) {
            // Cooperative cancellation (FGS 6-hour cap, worker stop): log as PAUSED
            // (DEC-005 — never presented as a failure) then PROPAGATE. Swallowing a
            // CancellationException would break structured coroutine cancellation; the
            // log write is done under NonCancellable to survive cancellation.
            withContext(NonCancellable) {
                syncLogDao.upsert(
                    logEntry.copy(
                        finishedAtEpochMs = System.currentTimeMillis(),
                        outcome = SyncOutcome.PAUSED,
                        message = "Interrupted by the system (FGS cap or cancellation)",
                    ),
                )
                syncLogDao.trimTo(keep = 50)
            }
            throw e
        } catch (e: Exception) {
            // Unexpected error → FAILED with message for the log
            SyncTrace.e("pass aborted", e)
            syncLogDao.upsert(
                logEntry.copy(
                    finishedAtEpochMs = System.currentTimeMillis(),
                    outcome = SyncOutcome.FAILED,
                    message = e.message?.take(200),
                ),
            )
            SyncOutcome.FAILED
        } finally {
            // Log rotation: keep only the last 50 passes (§8 task 13)
            syncLogDao.trimTo(keep = 50)
        }
    }
}
