package com.example.jellywrist.di

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildInfoTest {

    @Test
    fun `exposes the JellyWrist product name`() {
        assertEquals("JellyWrist", BuildInfo().appName)
    }
}
