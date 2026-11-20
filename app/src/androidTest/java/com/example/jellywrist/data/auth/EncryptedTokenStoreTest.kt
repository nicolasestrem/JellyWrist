package com.example.jellywrist.data.auth

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests of [EncryptedTokenStore].
 *
 * Instrumented, not JVM: [androidx.security.crypto.EncryptedSharedPreferences] relies
 * on the Android Keystore, absent from a JVM runner. Robolectric is excluded (risky on
 * AGP 9 / compileSdk 37), so real encryption verification is done on
 * emulator/watch — cf. `ServerRepositoryTest` for the pure-JVM part.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedTokenStoreTest {

    private lateinit var context: Context
    private lateinit var store: EncryptedTokenStore

    private val session = AuthSession(
        baseUrl = "http://192.0.2.10:8096",
        accessToken = "token-xyz",
        userId = "user-1",
        userName = "Avery",
        serverId = "server-1",
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = EncryptedTokenStore(context)
        store.clear() // isolation between runs
    }

    @Test
    fun emptyStoreReturnsTypedEmptyResult() {
        assertSame(SessionLoadResult.Empty, store.load())
    }

    @Test
    fun savedSessionRoundTrips() {
        store.save(session)

        assertEquals(session, store.load().sessionOrNull)
    }

    @Test
    fun savingAgainReplacesThePreviousSession() {
        store.save(session)
        val other = session.copy(accessToken = "token-2", userName = "Other", serverId = "server-2")

        store.save(other)

        assertEquals(other, store.load().sessionOrNull)
    }

    @Test
    fun nullServerIdRoundTrips() {
        store.save(session.copy(serverId = null))

        assertEquals(session.copy(serverId = null), store.load().sessionOrNull)
    }

    @Test
    fun clearRemovesTheSession() {
        store.save(session)

        store.clear()

        assertSame(SessionLoadResult.Empty, store.load())
    }

    @Test
    fun corruptedKeysetRecoversToEmptyStoreAndCanSaveAgain() {
        store.save(session)
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_KEYSET_ALIAS, "00")
            .commit()

        val recoveredStore = EncryptedTokenStore(context)

        assertSame(SessionLoadResult.RecoveredCorruptStore, recoveredStore.load())
        assertSame(SessionLoadResult.Empty, recoveredStore.load())

        recoveredStore.save(session)
        assertEquals(session, recoveredStore.load().sessionOrNull)
    }

    private companion object {
        const val PREFS_FILE = "jellywatch_auth"
        const val KEY_KEYSET_ALIAS = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    }
}
