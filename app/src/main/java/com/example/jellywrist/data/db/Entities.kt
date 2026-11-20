package com.example.jellywrist.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Pinnable collection type (PLAN.md §4.1). */
enum class CollectionType { PLAYLIST, ALBUM, ARTIST }

/**
 * A track's state in the download cycle (spec §"Data model", REQ-006/REQ-008).
 *
 * [UNSUPPORTED] is a **terminal state after download**, not a refusal to enqueue:
 * DEC-003 means that files with undecodable codecs (ALAC, WMA, DSD…) are
 * still downloaded, while DEC-004 requires reporting them at playback time rather
 * than failing at download. A track marked [UNSUPPORTED] therefore never reaches [READY]:
 * the player doesn't offer it, the UI shows it as unreadable.
 *
 * > Documented contradiction: `specs/03_MASTER_PRODUCTION_SPEC.md` (line 242) says these
 * > tracks are "not enqueued". The source hierarchy in AGENTS.md places DECISION_LOG
 * > LOCKED entries above the spec; DEC-003 therefore prevails. See GAP-011.
 */
enum class TrackState { TO_DOWNLOAD, DOWNLOADING, READY, UNSUPPORTED, ERROR, PENDING_BUDGET }

/**
 * Known Jellyfin server.
 *
 * [pinnedCertificateSha256] materializes the opt-in pinning of PLAN.md §3.1: a
 * certificate fingerprint explicitly accepted by the user, never a global
 * disabling of TLS verification (DEC-006).
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val baseUrl: String,
    val name: String,
    val pinnedCertificateSha256: String? = null,
)

/**
 * Authenticated account.
 *
 * Contains **no secrets**: the token lives exclusively in
 * [com.example.jellywrist.data.auth.TokenStore] (REQ-005). This table exists only to
 * show the current identity offline.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val userId: String,
    val serverId: String?,
    val userName: String,
)

/**
 * Jellyfin collection (playlist, album or artist) and its sync state.
 *
 * [pinned] is the sole user intent: the sync engine converges local content
 * toward the server content of pinned collections (PLAN.md §4.1).
 * [reconWatermark] records the last reconciliation of an artist (whose album list
 * evolves server-side without user action).
 */
@Entity(
    tableName = "collections",
    indices = [Index("type"), Index("pinned")],
)
data class CollectionEntity(
    @PrimaryKey val id: String,
    val type: CollectionType,
    val name: String,
    val sortName: String? = null,
    /** Carrier artist, for albums (display and grouping). */
    val artistName: String? = null,
    val pinned: Boolean = false,
    /**
     * **Local** collection (e.g. "500 random songs"): doesn't exist server-side.
     * The sync engine doesn't refresh it — it converges the cache toward its own
     * content (recon by identity), which protects its tracks from refcounted purge.
     */
    val localOnly: Boolean = false,
    /** Sum of server track sizes, shown before pinning (PLAN.md §4.4). */
    val estimatedBytes: Long = 0L,
    val trackCount: Int = 0,
    val lastSyncAtEpochMs: Long? = null,
    val reconWatermark: String? = null,
    /** Jellyfin primary image tag — serves as album-art cache key. */
    val imageTag: String? = null,
)

/**
 * Audio track and its local state.
 *
 * There is **no file path**: bytes are held by the Media3 cache
 * (`SimpleCache`) and addressed by [id] via `customCacheKey`. "Available offline"
 * is therefore expressed by `state == `[TrackState.READY], not by the existence of a file.
 */
@Entity(
    tableName = "tracks",
    indices = [Index("state"), Index("albumId")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val durationMs: Long = 0L,
    /** Audio codec reported by Jellyfin (`flac`, `mp3`, `alac`…) — basis of DEC-004 detection. */
    val codec: String? = null,
    val container: String? = null,
    /** Server-reported size, basis of capacity control before enqueueing. */
    val sizeBytes: Long = 0L,
    val state: TrackState = TrackState.TO_DOWNLOAD,
    /** Bytes already cached — feeds progress and Range-based resume. */
    val downloadedBytes: Long = 0L,
    val errorMessage: String? = null,
    val indexNumber: Int? = null,
    val imageTag: String? = null,
)

/**
 * n-n link collection ↔ track.
 *
 * The reference counter is what makes purge safe: a track is only removed
 * when it no longer belongs to **any** pinned collection (REQ-006, PLAN.md §4.4).
 * Cascade deletes prevent phantom links.
 */
@Entity(
    tableName = "collection_tracks",
    primaryKeys = ["collectionId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId")],
)
data class CollectionTrackEntity(
    val collectionId: String,
    val trackId: String,
    val sortIndex: Int = 0,
)

/** Outcome of a sync pass. */
enum class SyncOutcome { SUCCESS, PARTIAL, PAUSED, FAILED }

/**
 * Sync log viewable from settings (PLAN.md §4.2 point 7).
 *
 * [SyncOutcome.PAUSED] exists to never present the 6-hour FGS cap as a failure:
 * the UI must say "paused — will resume" (DEC-005).
 */
@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val added: Int = 0,
    val removed: Int = 0,
    val failed: Int = 0,
    val deferredForBudget: Int = 0,
    val outcome: SyncOutcome = SyncOutcome.SUCCESS,
    val message: String? = null,
)
