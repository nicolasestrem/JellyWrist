package com.example.jellywrist.data.repo

import com.example.jellywrist.data.auth.AuthBootstrapResult
import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.auth.SessionLoadResult
import com.example.jellywrist.data.auth.TokenStore
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The session memory cache: the first EncryptedSharedPreferences read used to run on the
 * main thread at every screen — after warm-up the store must never be hit again, and
 * every mutation must keep the cache coherent.
 */
class AuthSessionCacheTest {

    private class CountingTokenStore(
        private var result: SessionLoadResult = SessionLoadResult.Empty,
    ) : TokenStore {
        constructor(session: AuthSession) : this(SessionLoadResult.Restored(session))

        var loads = 0

        override fun save(session: AuthSession) {
            result = SessionLoadResult.Restored(session)
        }

        override fun load(): SessionLoadResult {
            loads++
            return result
        }

        override fun clear() {
            result = SessionLoadResult.Empty
        }
    }

    private val backend = object : JellyfinBackend {
        override fun discoverServers(): Flow<org.jellyfin.sdk.model.api.ServerDiscoveryInfo> = emptyFlow()
        override fun apiClient(baseUrl: String, accessToken: String?): ApiClient =
            error("not used")
    }

    private val session = AuthSession(
        baseUrl = "https://jelly.example",
        accessToken = "token",
        userId = "user-1",
        userName = "Avery",
        serverId = "server-1",
    )

    @Test
    fun `the store is read once whatever the number of session reads`() = runTest {
        val store = CountingTokenStore(session)
        val repository = AuthRepository(backend, store)

        repository.warmUp()
        repeat(5) { assertEquals(session, repository.currentSession()) }
        assertEquals(session, repository.awaitSession())

        assertEquals(1, store.loads)
    }

    @Test
    fun `sign out keeps the cache coherent without another store read`() = runTest {
        val store = CountingTokenStore(session)
        val repository = AuthRepository(backend, store)
        repository.warmUp()

        repository.signOut()

        assertNull(repository.currentSession())
        assertNull(repository.awaitSession())
        assertEquals(1, store.loads)
    }

    @Test
    fun `a cold currentSession still reads the store and warms the cache`() {
        val store = CountingTokenStore(session)
        val repository = AuthRepository(backend, store)

        assertEquals(session, repository.currentSession())
        assertEquals(session, repository.currentSession())

        assertEquals(1, store.loads)
    }

    @Test
    fun `corrupt store recovery is visible to bootstrap callers`() = runTest {
        val store = CountingTokenStore(SessionLoadResult.RecoveredCorruptStore)
        val repository = AuthRepository(backend, store)

        assertEquals(
            AuthBootstrapResult.SignedOut(recoveredCorruptStore = true),
            repository.awaitBootstrap(),
        )
        assertNull(repository.currentSession())
        assertEquals(1, store.loads)
    }

    @Test
    fun `concurrent bootstrap calls read the store once`() = runTest {
        val store = CountingTokenStore(session)
        val repository = AuthRepository(backend, store)

        coroutineScope {
            List(20) { async(Dispatchers.Default) { repository.awaitBootstrap() } }
                .forEach { it.await() }
        }

        assertEquals(1, store.loads)
    }
}
