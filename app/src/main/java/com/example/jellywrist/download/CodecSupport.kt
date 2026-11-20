package com.example.jellywrist.download

import com.example.jellywrist.data.db.TrackState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Codec compatibility detection (DEC-003 / DEC-004).
 *
 * Codecs **not decodable** by ExoPlayer are still downloaded (DEC-003: we keep the
 * original file), but their final state is [TrackState.UNSUPPORTED] rather than
 * [TrackState.READY] — the player does not offer them, the UI flags them as unplayable.
 *
 * The list of decodable codecs is extracted from the ExoPlayer documentation (FLAC, MP3,
 * AAC, Vorbis, Opus, WAV, PCM). Any other codec (ALAC, WMA, DSD, DSF, APE, MPC…) results
 * in [TrackState.UNSUPPORTED].
 */
@Singleton
class CodecSupport @Inject constructor() {

    /**
     * Determines the final state of a track once downloaded, based on its codec.
     *
     * If the codec is `null` (missing metadata) or in the decodable set, returns
     * [TrackState.READY]. Otherwise returns [TrackState.UNSUPPORTED].
     */
    fun finalStateForCodec(codec: String?): TrackState =
        if (codec.isNullOrBlank() || codec.trim().lowercase() in DECODABLE_CODECS) {
            // Missing/blank codec: Jellyfin sometimes omits the metadata — refusing by
            // default would deprive the user of perfectly playable tracks. Deliberate
            // optimism.
            TrackState.READY
        } else {
            TrackState.UNSUPPORTED
        }

    private companion object {
        /**
         * Codecs decodable by ExoPlayer on Wear OS.
         *
         * `m4a(aac)` is normalized to `aac` — Jellyfin sometimes reports the container in
         * the codec field. We also handle `alac` within AAC even though it is in the
         * non-decodable set: lowercase normalization is sufficient because the two sets
         * are disjoint.
         */
        val DECODABLE_CODECS = setOf(
            "flac", "mp3", "aac", "m4a", "vorbis", "opus", "wav", "pcm",
            // Common containers reported as codec by Jellyfin:
            "m4a(aac)", "mp4(aac)",
        )
    }
}
