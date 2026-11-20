package com.example.jellywrist.data.repo

import com.example.jellywrist.data.auth.AuthResult
import com.example.jellywrist.data.auth.AuthBootstrapResult
import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.auth.QuickConnectHandle
import com.example.jellywrist.data.auth.QuickConnectState
import com.example.jellywrist.data.auth.SessionLoadResult
import com.example.jellywrist.data.auth.TokenStore
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.QuickConnectApi
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Authentication against a Jellyfin server (PLAN.md §3.2).
 *
 * Quick Connect is the primary mode — no keyboard is required on the watch:
 * 1. [initiateQuickConnect] obtains a `secret` + a displayable `code`;
 * 2. [pollQuickConnect] polls the state until the user approves from a Jellyfin client
 *    that is already signed in;
 * 3. [authenticateWithQuickConnect] exchanges the approved secret for a token.
 * Quick Connect is **the only authentication path** (DEC-010, owner decision
 * 2026-08-08): no password ever passes through the watch.
 *
 * Depends on [JellyfinBackend] rather than [org.jellyfin.sdk.Jellyfin]: testable on the JVM
 * without a `Context`. Any successful session is persisted in [TokenStore] before being returned.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val backend: JellyfinBackend,
    private val tokenStore: TokenStore,
) {

    /**
     * Memory cache over [TokenStore]: the first `load()` opens
     * EncryptedSharedPreferences (MasterKey + Tink keyset + file I/O) and used to run on
     * the main thread from six call sites. Once warm, [currentSession] never touches disk.
     * `@Volatile`: warmed on an I/O dispatcher, read from the main thread.
     */
    private val bootstrapLock = Any()

    @Volatile
    private var cachedBootstrap: AuthBootstrapResult? = null

    /**
     * Loads the persisted session on [Dispatchers.IO] so later [currentSession] calls are
     * memory-only. Launched first thing at app start; idempotent and cheap once warm.
     */
    suspend fun warmUp() {
        awaitBootstrap()
    }

    /** Loads and classifies the encrypted session exactly once per process. */
    suspend fun awaitBootstrap(): AuthBootstrapResult = withContext(Dispatchers.IO) {
        synchronized(bootstrapLock) { bootstrapLocked() }
    }

    /** [currentSession] without the risk of a first Keystore read on the caller's thread. */
    suspend fun awaitSession(): AuthSession? {
        return awaitBootstrap().sessionOrNull
    }

    /**
     * Opens a Quick Connect request on [baseUrl].
     *
     * @return `null` if Quick Connect is unavailable (disabled server-side, endpoint
     *   missing, network error) — the UI then shows the "Quick Connect required" state.
     */
    suspend fun initiateQuickConnect(baseUrl: String): QuickConnectHandle? = try {
        val result = QuickConnectApi(backend.apiClient(baseUrl)).initiateQuickConnect().content
        QuickConnectHandle(secret = result.secret, code = result.code)
    } catch (e: CancellationException) {
        throw e // never swallow coroutine cancellation
    } catch (_: Exception) {
        // Deliberate silent fallback: the UI presents "Quick Connect required" with a retry
        // (DEC-010) — the exact error has no actionable value for the user.
        null
    }

    /**
     * Polls once the state of the request identified by [secret].
     *
     * An unknown or stale secret makes the server respond with a 4xx: translated into
     * [QuickConnectState.Expired] rather than an error; it is a nominal state of the flow.
     */
    suspend fun quickConnectState(baseUrl: String, secret: String): QuickConnectState = try {
        val result = QuickConnectApi(backend.apiClient(baseUrl)).getQuickConnectState(secret).content
        if (result.authenticated) QuickConnectState.Approved else QuickConnectState.Pending
    } catch (e: CancellationException) {
        throw e
    } catch (e: InvalidStatusException) {
        if (e.status in CLIENT_ERROR_RANGE) QuickConnectState.Expired else throw e
    }

    /**
     * Polls the state every [POLL_INTERVAL] until a terminal state
     * ([QuickConnectState.Approved], [QuickConnectState.Expired] or
     * [QuickConnectState.Unavailable]).
     *
     * Beyond [POLL_TIMEOUT] without approval, emits [QuickConnectState.Expired] and
     * finishes: a Quick Connect request is not eternal and the watch must not poll
     * forever (battery). Cold flow: cancellation stops the polling.
     *
     * **This flow never throws** (other than cancellation), and that is a contract, not a
     * convenience: its three collectors run in `launch` blocks without an exception handler —
     * on the watch, `appScope` is a bare `SupervisorJob`, and an exception escaping it kills
     * the process instead of replying `SETUP_RESULT` to the phone. A transport outage is
     * therefore a **state**, not an exception: it is absorbed for [FAILURE_GRACE] (a
     * two-second Wi-Fi handover must not doom a wait that can last five minutes), then
     * reported as [QuickConnectState.Unavailable].
     */
    fun pollQuickConnect(baseUrl: String, secret: String): Flow<QuickConnectState> = flow {
        var waited: Duration = 0.milliseconds
        var failingFor: Duration = 0.milliseconds

        while (true) {
            val state = try {
                quickConnectState(baseUrl, secret)
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (_: Exception) {
                // Transient until proven otherwise: the code stays displayed, we retry.
                null
            }

            if (state == null) {
                if (failingFor >= FAILURE_GRACE) {
                    emit(QuickConnectState.Unavailable)
                    return@flow
                }
                failingFor += POLL_INTERVAL
            } else {
                failingFor = 0.milliseconds
                emit(state)
                if (state != QuickConnectState.Pending) return@flow
            }

            if (waited >= POLL_TIMEOUT) {
                emit(QuickConnectState.Expired)
                return@flow
            }

            delay(POLL_INTERVAL)
            waited += POLL_INTERVAL
        }
    }

    /** Exchanges an approved Quick Connect secret for an authenticated session. */
    suspend fun authenticateWithQuickConnect(baseUrl: String, secret: String): AuthResult = try {
        val result = UserApi(backend.apiClient(baseUrl))
            .authenticateWithQuickConnect(QuickConnectDto(secret))
            .content
        result.toAuthResult(baseUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: InvalidStatusException) {
        if (e.status in CLIENT_ERROR_RANGE) AuthResult.QuickConnectExpired else AuthResult.Failed(e)
    } catch (e: Exception) {
        AuthResult.Failed(e)
    }

    /**
     * Persisted session, or `null` if the user is not authenticated.
     *
     * First call after process start hits the encrypted store (prefer [awaitSession] or a
     * prior [warmUp] off the main thread); afterwards this reads the memory cache, which
     * every mutation below keeps coherent.
     */
    fun currentSession(): AuthSession? {
        return synchronized(bootstrapLock) { bootstrapLocked().sessionOrNull }
    }

    /** Clears the session: the encrypted token is removed from storage. */
    fun signOut() {
        tokenStore.clear()
        synchronized(bootstrapLock) {
            cachedBootstrap = AuthBootstrapResult.SignedOut()
        }
    }

    /**
     * Translates the SDK response into an [AuthResult] and persists the session on success.
     *
     * A missing/blank token or a missing user is a failure: without them, no later
     * authenticated call would work — better to fail here than later.
     */
    private fun AuthenticationResult.toAuthResult(baseUrl: String): AuthResult {
        val token = accessToken?.takeIf { it.isNotBlank() }
            ?: return AuthResult.Failed(IllegalStateException("Response missing AccessToken"))
        val user = user
            ?: return AuthResult.Failed(IllegalStateException("Response missing User"))

        val session = AuthSession(
            baseUrl = baseUrl,
            accessToken = token,
            userId = user.id.toString(),
            userName = user.name.orEmpty(),
            serverId = serverId,
        )
        tokenStore.save(session)
        synchronized(bootstrapLock) {
            cachedBootstrap = AuthBootstrapResult.Authenticated(session)
        }
        return AuthResult.Success(session)
    }

    private fun bootstrapLocked(): AuthBootstrapResult = cachedBootstrap ?: when (val stored = tokenStore.load()) {
        is SessionLoadResult.Restored -> AuthBootstrapResult.Authenticated(stored.session)
        SessionLoadResult.Empty -> AuthBootstrapResult.SignedOut()
        SessionLoadResult.RecoveredCorruptStore ->
            AuthBootstrapResult.SignedOut(recoveredCorruptStore = true)
    }.also {
        // Which of the three it was decides whether an app that starts on the onboarding
        // screen is behaving correctly or has just lost the user's session; nothing recorded
        // it, so a watch that stopped syncing looked identical either way. No session field
        // is logged (REQ-005).
        Log.i(
            TAG,
            "Session bootstrap: " + when (it) {
                is AuthBootstrapResult.Authenticated -> "restored"
                is AuthBootstrapResult.SignedOut ->
                    if (it.recoveredCorruptStore) "signed out after store recovery" else "signed out"
            },
        )
        cachedBootstrap = it
    }

    private companion object {
        private const val TAG = "JellyAuth"

        /** Quick Connect polling cadence (responsiveness / battery trade-off). */
        val POLL_INTERVAL = 2.seconds

        /** Total budget before giving up on polling. */
        val POLL_TIMEOUT = 5.minutes

        /**
         * Tolerated server silence before concluding [QuickConnectState.Unavailable].
         *
         * Must stay well under the phone's 90 s `RESULT_TIMEOUT`
         * (`WatchSetupController`): a genuinely dead server must be named as such
         * while the companion is still listening, otherwise it only shows a timeout
         * and the user never learns the cause.
         */
        val FAILURE_GRACE = 20.seconds

        val CLIENT_ERROR_RANGE = 400..499
    }
}
