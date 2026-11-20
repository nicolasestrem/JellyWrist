package com.example.jellywrist.download

import com.example.jellywrist.data.db.TrackState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests of [CodecSupport] — no Android or Media3 dependencies.
 *
 * Verifies the classification of decodable vs non-decodable codecs by
 * ExoPlayer (DEC-003 / DEC-004, PLAN.md §8, task 6).
 */
class CodecSupportTest {

    private val codecSupport = CodecSupport()

    // ── Decodable codecs → READY ──

    @Test
    fun `flac codec is decodable`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("flac"))
    }

    @Test
    fun `mp3 codec is decodable`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("mp3"))
    }

    @Test
    fun `aac codec is decodable`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("aac"))
    }

    @Test
    fun `m4a container is decodable`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("m4a"))
    }

    @Test
    fun `m4a(aac) Jellyfin format is decodable`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("m4a(aac)"))
    }

    @Test
    fun `mp4(aac) Jellyfin format is decodable`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("mp4(aac)"))
    }

    @Test
    fun `vorbis codec is decodable`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("vorbis"))
    }

    @Test
    fun `opus codec is decodable`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("opus"))
    }

    @Test
    fun `wav codec is decodable`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("wav"))
    }

    @Test
    fun `pcm codec is decodable`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("pcm"))
    }

    @Test
    fun `null codec defaults to READY`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec(null))
    }

    @Test
    fun `empty codec defaults to READY`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec(""))
    }

    @Test
    fun `uppercase codec is normalized`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("FLAC"))
    }

    @Test
    fun `mixed case codec is normalized`() {
        assertEquals(TrackState.READY, codecSupport.finalStateForCodec("Mp3"))
    }

    // ── Non-decodable codecs → UNSUPPORTED ──

    @Test
    fun `alac codec is not decodable`() {
        assertEquals(TrackState.UNSUPPORTED, codecSupport.finalStateForCodec("alac"))
    }

    @Test
    fun `wma codec is not decodable`() {
        assertEquals(TrackState.UNSUPPORTED, codecSupport.finalStateForCodec("wma"))
    }

    @Test
    fun `dsd codec is not decodable`() {
        assertEquals(TrackState.UNSUPPORTED, codecSupport.finalStateForCodec("dsd"))
    }

    @Test
    fun `dsf codec is not decodable`() {
        assertEquals(TrackState.UNSUPPORTED, codecSupport.finalStateForCodec("dsf"))
    }

    @Test
    fun `ape codec is not decodable`() {
        assertEquals(TrackState.UNSUPPORTED, codecSupport.finalStateForCodec("ape"))
    }

    @Test
    fun `mpc codec is not decodable`() {
        assertEquals(TrackState.UNSUPPORTED, codecSupport.finalStateForCodec("mpc"))
    }
}
