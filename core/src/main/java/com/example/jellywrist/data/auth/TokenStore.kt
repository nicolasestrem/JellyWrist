package com.example.jellywrist.data.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence of the authenticated session.
 *
 * Deliberately minimal contract: the token is a secret and must only exist in encrypted
 * storage (REQ-005 / DEC-002). Abstracted so that
 * [com.example.jellywrist.data.repo.AuthRepository] is testable on the JVM without the Keystore.
 */
interface TokenStore {
    fun save(session: AuthSession)

    fun load(): SessionLoadResult

    fun clear()
}

/**
 * Production implementation: [EncryptedSharedPreferences] backed by the Android Keystore
 * (AES256-GCM master key, keys encrypted with AES256-SIV, values with AES256-GCM).
 *
 * **The token is never logged** — no `Log`, no `println`, no exception message.
 * It also never passes through Room or plain-text preferences (REQ-005).
 * The preferences file is created lazily: instantiating the class triggers no Keystore
 * I/O (useful for Hilt injection at startup).
 *
 * Using `commit()` is deliberate, to persist the token and the session wipe before
 * returning to the caller (REQ-005).
 */
@SuppressLint("ApplySharedPref", "UseKtx")
@Singleton
class EncryptedTokenStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TokenStore {

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun preferences(): SharedPreferences = cachedPrefs ?: synchronized(this) {
        cachedPrefs ?: createPreferences().also { cachedPrefs = it }
    }

    override fun save(session: AuthSession) {
        preferences().edit()
            .putString(KEY_BASE_URL, session.baseUrl)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USER_NAME, session.userName)
            .putString(KEY_SERVER_ID, session.serverId)
            .commit()
    }

    override fun load(): SessionLoadResult = try {
        val prefs = preferences()
        val baseUrl = prefs.getString(KEY_BASE_URL, null)
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val userId = prefs.getString(KEY_USER_ID, null)
        val userName = prefs.getString(KEY_USER_NAME, null)

        when {
            listOf(baseUrl, accessToken, userId, userName).all { it == null } ->
                SessionLoadResult.Empty
            baseUrl == null || accessToken == null || userId == null || userName == null -> {
                resetUnreadableStore()
                SessionLoadResult.RecoveredCorruptStore
            }
            else -> SessionLoadResult.Restored(
                AuthSession(
                    baseUrl = baseUrl,
                    accessToken = accessToken,
                    userId = userId,
                    userName = userName,
                    serverId = prefs.getString(KEY_SERVER_ID, null),
                ),
            )
        }
    } catch (failure: Exception) {
        if (!failure.isRecoverableStorageFailure()) throw failure
        resetUnreadableStore(failure)
        SessionLoadResult.RecoveredCorruptStore
    }

    override fun clear() {
        Log.i(TAG, "Session cleared on request (sign-out or factory reset)")
        try {
            preferences().edit().clear().commit()
        } catch (failure: Exception) {
            if (!failure.isRecoverableStorageFailure()) throw failure
            resetUnreadableStore(failure)
        }
    }

    private fun createPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Deletes only the re-authenticatable session store and its app-owned key.
     *
     * This destroys the user's session, so it announces itself. The recovery was silent, and
     * silence here is indistinguishable from an ordinary sign-out: both leave a preferences
     * file holding nothing but its Tink keysets. Someone who was signed in yesterday, is
     * signed out today and finds nothing downloaded had no way to learn which of the two had
     * happened, and neither did anyone reading a bug report about it.
     *
     * Only the failure's *class* is recorded. A `GeneralSecurityException` message can carry
     * key material or ciphertext, and REQ-005 keeps both out of the log.
     */
    private fun resetUnreadableStore(cause: Throwable? = null) = synchronized(this) {
        Log.w(
            TAG,
            "Encrypted session store was unreadable and has been reset; the user must sign in " +
                "again (cause: ${cause?.javaClass?.name ?: "incomplete session record"})",
        )
        cachedPrefs = null
        context.deleteSharedPreferences(PREFS_FILE)
        runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }.onFailure { Log.w(TAG, "Master key could not be deleted: ${it.javaClass.name}") }
    }

    private fun Throwable.isRecoverableStorageFailure(): Boolean =
        generateSequence(this) { it.cause }
            .any { it is GeneralSecurityException || it is IOException }

    internal companion object {
        private const val TAG = "JellyAuth"

        const val PREFS_FILE = "jellywrist_auth"

        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_SERVER_ID = "server_id"
    }
}
