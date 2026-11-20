package com.example.jellywrist.di

import javax.inject.Inject
import javax.inject.Singleton

/** M0 proof that the Hilt graph builds and injects end to end. */
@Singleton
class BuildInfo @Inject constructor() {
    val appName: String = "JellyWrist"
}
