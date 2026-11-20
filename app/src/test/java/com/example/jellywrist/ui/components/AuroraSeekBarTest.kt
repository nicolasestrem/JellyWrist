package com.example.jellywrist.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AuroraSeekBarTest {
    @Test
    fun `seek position clamps normalized fraction to media duration`() {
        assertEquals(0L, seekPositionForFraction(-0.25f, durationMs = 10_000L))
        assertEquals(5_000L, seekPositionForFraction(0.5f, durationMs = 10_000L))
        assertEquals(10_000L, seekPositionForFraction(1.25f, durationMs = 10_000L))
    }
}
