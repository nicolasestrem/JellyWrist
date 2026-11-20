package com.example.jellywrist.sync

/**
 * Artist-level batching result (PLAN.md §4.4).
 *
 * "Download everything for this artist" resolves the album list, enqueues greedily up
 * to the available cap and leaves the rest in [pendingBudget] to be released when space
 * frees up.
 */
data class ArtistBatchResult(
    /** Albums to enqueue immediately, in greedy order. */
    val toEnqueue: List<ArtistAlbum>,
    /** Albums deferred for lack of capacity (PENDING_BUDGET), in greedy order. */
    val pendingBudget: List<ArtistAlbum>,
)

/**
 * An artist's album, as seen by the batcher (PLAN.md §4.4).
 *
 * [albumId] and [totalSizeBytes] are sufficient for capacity calculation.
 */
data class ArtistAlbum(
    val albumId: String,
    val albumName: String,
    val totalSizeBytes: Long,
)

/**
 * Artist-level batching — pure function (PLAN.md §4.4).
 *
 * Resolves an artist's album list into a greedy enqueue order: albums are taken in the
 * given order until the cap is reached. The rest goes into [ArtistBatchResult.pendingBudget].
 *
 * An album larger than the total capacity is deferred, never partially accepted.
 * The order is deterministic: the caller provides the order, the batcher preserves it.
 *
 * @param albums the artist's albums, in the caller's desired order
 * @param budgetBytes available bytes (after subtracting used quota and the reserve)
 * @return [ArtistBatchResult] with the albums to enqueue and those deferred
 */
fun artistBatch(
    albums: List<ArtistAlbum>,
    budgetBytes: Long,
): ArtistBatchResult {
    if (budgetBytes <= 0L || albums.isEmpty()) {
        return ArtistBatchResult(
            toEnqueue = emptyList(),
            pendingBudget = albums,
        )
    }

    val toEnqueue = mutableListOf<ArtistAlbum>()
    val pendingBudget = mutableListOf<ArtistAlbum>()
    var remaining = budgetBytes

    for (album in albums) {
        if (album.totalSizeBytes <= remaining) {
            toEnqueue.add(album)
            remaining -= album.totalSizeBytes
        } else {
            pendingBudget.add(album)
        }
    }

    return ArtistBatchResult(
        toEnqueue = toEnqueue,
        pendingBudget = pendingBudget,
    )
}
