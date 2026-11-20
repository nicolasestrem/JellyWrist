package com.example.jellywrist.di

import android.content.Context
import com.google.android.horologist.audio.SystemAudioRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Watch audio outputs (Horologist `audio`).
 *
 * [SystemAudioRepository] observes the active output (BT headset / speaker / remote)
 * and drives the system volume — the basis of the speaker guard (REQ-009) and per-volume
 * control (PLAN.md §5). Singleton: it sits on top of MediaRouter, costly to instantiate.
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideSystemAudioRepository(
        @ApplicationContext context: Context,
    ): SystemAudioRepository = SystemAudioRepository.fromContext(context)
}
