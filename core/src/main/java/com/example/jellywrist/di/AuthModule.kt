package com.example.jellywrist.di

import com.example.jellywrist.data.auth.EncryptedTokenStore
import com.example.jellywrist.data.auth.TokenStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the authentication layer.
 *
 * Only the binding of the [TokenStore] port is needed:
 * [com.example.jellywrist.data.repo.AuthRepository] is built by constructor
 * injection from [com.example.jellywrist.data.jellyfin.JellyfinBackend] (provided
 * by [JellyfinModule]) and [TokenStore].
 *
 * See PLAN.md §3.2 (authentication) and §11 (layout `di/` + `data/auth/`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindTokenStore(impl: EncryptedTokenStore): TokenStore
}
