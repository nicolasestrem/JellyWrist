package com.example.jellywrist.data.jellyfin

/**
 * ExoPlayer codec support — pure function, testable on the JVM.
 *
 * Determines whether a codec advertised by Jellyfin is natively decodable by ExoPlayer on
 * Wear OS. Supported codecs are `flac`, `mp3`, `aac`, `m4a` (AAC container),
 * `vorbis`, `opus`, `wav` and `pcm`.
 *
 * DEC-003 rules that non-decodable codecs are **downloaded** but land in
 * [com.example.jellywrist.data.db.TrackState.UNSUPPORTED]: the UI flags them as
 * unplayable but does not delete them, and the player never offers them.
 *
 * DEC-004 requires this detection to happen at sync-diff time,
 * never at playback — see `specs/DECISION_LOG.md`.
 *
 * PLAN.md §5: "FLAC, MP3, AAC, OGG Vorbis and Opus natively decoded by ExoPlayer".
 * Codecs not listed here (alac, wma, dsd, dsf, ape, mpc) are treated as non-decodable.
 *
 * @param codec Jellyfin codec identifier (lowercase, e.g. `flac`, `aac`, `alac`).
 *              `null` is treated as unknown → non-decodable (conservative).
 * @return `true` if ExoPlayer can decode this codec on Wear OS.
 */
fun isCodecDecodable(codec: String?): Boolean {
    if (codec == null) return false
    return codec.lowercase() in DECODABLE_CODECS
}

/** Closed list of codecs decodable by ExoPlayer (exposed for tests). */
val DECODABLE_CODECS: Set<String> = setOf(
    "flac",
    "mp3",
    "aac",
    "m4a", // conteneur AAC courant
    "vorbis",
    "opus",
    "wav",
    "pcm",
)
