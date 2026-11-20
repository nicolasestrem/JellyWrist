package com.example.jellywrist.companion

import com.example.jellywrist.data.auth.AuthResult
import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.auth.QuickConnectState
import com.example.jellywrist.data.net.PinRegistry
import com.example.jellywrist.data.reset.ServerDataReset
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.repo.ServerRepository
import com.example.jellywrist.data.repo.ServerValidation
import com.example.jellywrist.data.settings.SettingsRepository
import com.example.jellywrist.sync.PeriodicSyncArmer
import com.example.jellywrist.wire.SetupQcCode
import com.example.jellywrist.wire.SetupRequest
import com.example.jellywrist.wire.SetupResult
import com.example.jellywrist.wire.WireErrors
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.toWireBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of the provisioning triggered remotely by the phone app.
 *
 * [Done] and [Failed] are **sticky**: they remain exposed until the next provisioning
 * or until [CompanionSetupCoordinator.reset]. Any observer created afterwards therefore
 * receives them immediately — see the caveat on [CompanionSetupCoordinator.state].
 */
sealed interface RemoteSetupState {
    data object Idle : RemoteSetupState
    data object Validating : RemoteSetupState
    data class AwaitingApproval(val code: String) : RemoteSetupState
    data class Done(val userName: String) : RemoteSetupState
    data class Failed(val error: String) : RemoteSetupState
}

/**
 * Orchestrates the Quick Connect requested by the phone without depending on the Data Layer
 * transport.
 *
 * The entire provisioning runs in the application scope: the Wearable service that receives
 * the message may be destroyed as soon as its callback returns.
 *
 * **Process-survival risk (E2E audit required):** Quick Connect polling can last up to
 * 5 minutes (server-side timeout). If Android reclaims the watch's process during that
 * window, the phone will see a timeout instead of SETUP_RESULT. This risk is not mitigated
 * by a foreground service (a deliberate choice: the companion is optional). The first E2E
 * run on paired emulators must verify this case.
 */
@Singleton
class CompanionSetupCoordinator @Inject constructor(
    private val serverRepository: ServerRepository,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val pinRegistry: PinRegistry,
    private val serverDataReset: ServerDataReset,
    private val periodicSyncArmer: PeriodicSyncArmer,
    private val appScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RemoteSetupState>(RemoteSetupState.Idle)

    /**
     * Current state — **process-scoped**, like this singleton.
     *
     * A terminal state lingers here after the outcome: an observer created later (a fresh
     * home screen after a sign-out, for example) receives it right away and would mistake
     * it for an outcome concerning itself. That is what [reset] is for.
     */
    val state: StateFlow<RemoteSetupState> = _state.asStateFlow()

    /**
     * Provisioning in flight — **the entire operation**, validation included.
     *
     * Tracking only the Quick Connect polling was not enough: it only exists at the very end,
     * and a cancellation arriving during server validation or while opening the Quick Connect
     * request therefore found nothing to cancel. The coroutine kept running and could persist
     * a session **after** the factory reset.
     */
    private var setupJob: Job? = null

    /** Serializes replacement of [setupJob]: the old one dies before the new one is born. */
    private val setupMutex = Mutex()

    /**
     * Cancels the in-flight provisioning, clears residual state, then runs [afterReset]
     * **without releasing the lock**.
     *
     * Suspends **deliberately**: the sign-out must know the provisioning is dead before
     * erasing the token, otherwise an in-flight authentication would resurrect the account
     * the user just removed.
     *
     * [afterReset] is the reason this signature exists. Cancelling does not take the lock:
     * `reset()` alone returned before the factory reset finished, and a SETUP request landing
     * during the purge — which takes a while — started a full Quick Connect. That provisioning
     * could authenticate **after** the token was erased (erased last) and leave the watch
     * signed in to the account that had just been removed. Running the purge here puts it
     * under the same lock as [handleSetup]: a concurrent request waits for it to finish, then
     * provisions a genuinely blank watch — which is the intended flow, not the race.
     *
     * [afterReset] must not call anything that reenters this coordinator: the mutex is not
     * reentrant.
     */
    suspend fun reset(afterReset: suspend () -> Unit = {}) = setupMutex.withLock {
        setupJob?.cancelAndJoin()
        setupJob = null
        _state.value = RemoteSetupState.Idle
        afterReset()
    }

    /**
     * Handles a setup request and sends the reply messages back to the emitting node.
     *
     * Returns when the provisioning completes — or when a more recent request, or [reset],
     * has cancelled it.
     */
    suspend fun handleSetup(
        request: SetupRequest,
        reply: suspend (path: String, payload: ByteArray) -> Unit,
    ) {
        val job = setupMutex.withLock {
            // A new request always replaces the previous one, whatever stage it is at.
            setupJob?.cancelAndJoin()
            _state.value = RemoteSetupState.Validating
            appScope.launch { runSetup(request, reply) }.also { setupJob = it }
        }
        job.join()
    }

    private suspend fun runSetup(
        request: SetupRequest,
        reply: suspend (path: String, payload: ByteArray) -> Unit,
    ) {
        val existing = authRepository.currentSession()
        if (existing != null && existing.matches(request)) {
            reply(
                WirePaths.SETUP_RESULT,
                SetupResult(
                    ok = true,
                    alreadyConfigured = true,
                    userName = existing.userName,
                ).toWireBytes(),
            )
            _state.value = RemoteSetupState.Done(existing.userName)
            return
        }

        // A session exists but does not match the requested configuration: this is a
        // reconfiguration (the phone's "Reconfigure" button), not an acknowledgement.
        // The full Quick Connect flow is replayed below; the old server's catalog is only
        // purged AFTER authentication — an expired code or an unreachable server must leave
        // the watch exactly in its original state.
        val reconfiguring = existing != null

        // The requested pin is **staged**: the in-memory registry is enough for the TLS
        // handshake that follows, and nothing is persisted until the setup succeeds.
        // Persisting it up front made the previous server unreachable as soon as a
        // reconfiguration failed — the retained session then pointed at a certificate the
        // trust manager had just stopped accepting. Staging is a state distinct from the
        // accepted fingerprint: the settings collector re-emits on the slightest change and
        // would otherwise overwrite the certificate being validated.
        val previousPin = settingsRepository.settings.first().pinnedCertificateSha256
        val requestedPin = request.pinnedCertSha256
        val pinStaged = requestedPin != null && requestedPin != previousPin
        if (pinStaged) pinRegistry.stage(requestedPin)

        var configured = false
        // The user name once the session is persisted — the marker that distinguishes
        // "failed" from "succeeded, but the announcement did not go through" in the final
        // catch-up.
        var configuredUserName: String? = null
        try {
            val validation = serverRepository.validateServer(request.serverUrl)
            val baseUrl = when (validation) {
                is ServerValidation.Valid -> validation.server.baseUrl
                is ServerValidation.InvalidUrl -> {
                    fail(WireErrors.INVALID_URL, reply)
                    return
                }

                is ServerValidation.Unreachable -> {
                    fail(WireErrors.UNREACHABLE, reply)
                    return
                }
            }

            val handle = authRepository.initiateQuickConnect(baseUrl)
            if (handle == null) {
                fail(WireErrors.QUICKCONNECT_UNAVAILABLE, reply)
                return
            }

            reply(WirePaths.SETUP_QC, SetupQcCode(handle.code).toWireBytes())
            _state.value = RemoteSetupState.AwaitingApproval(handle.code)

            authRepository.pollQuickConnect(baseUrl, handle.secret).collect { qcState ->
                when (qcState) {
                    QuickConnectState.Pending -> Unit
                    QuickConnectState.Approved -> {
                        when (val result = authRepository.authenticateWithQuickConnect(baseUrl, handle.secret)) {
                            is AuthResult.Success -> {
                                // The token is already persisted: the writes that follow from
                                // it become indivisible. A cancellation slipping in between
                                // authentication and the purge would leave the new session
                                // sitting on the OLD server's catalog — precisely the
                                // inconsistency the reconfiguration must remove.
                                // The block stays limited to local writes: sending to the
                                // phone goes through Play Services and has no business inside
                                // a region a sign-out is waiting on.
                                withContext(NonCancellable) {
                                    configuredUserName = result.session.userName
                                    if (pinStaged) {
                                        settingsRepository.setPinnedCertificate(requestedPin)
                                        // Promoted from staged to accepted with no window in
                                        // between: the settings collector will only catch up
                                        // with the write after the fact.
                                        pinRegistry.update(requestedPin)
                                        pinRegistry.clearStaged()
                                    }
                                    if (reconfiguring) serverDataReset.clearServerData()
                                    // GAP-010: after a factory reset, `reconfiguring` is
                                    // false and `clearServerData()` (the only re-arming
                                    // call site on this path) is skipped — the watch then
                                    // looked configured but silently never synced again.
                                    // Arming is idempotent, so the reconfiguration path
                                    // doing it twice is harmless.
                                    periodicSyncArmer.arm()
                                    // Set LAST, and that is the whole point: `configured`
                                    // means "the writes went through", and it alone keeps
                                    // the `finally` from undoing the pin. Setting it up
                                    // front let a staged pin outlive a failed write
                                    // (DataStore, WorkManager) in memory — now intercepted
                                    // below instead of killing the process. The registry
                                    // would then have trusted, for the whole life of the
                                    // process, a certificate no setting records. The token,
                                    // on the other hand, is already persisted:
                                    // [configuredUserName] says so, and it is set first.
                                    configured = true
                                }
                                reply(WirePaths.SETUP_RESULT, SetupResult(ok = true, userName = result.session.userName).toWireBytes())
                                _state.value = RemoteSetupState.Done(result.session.userName)
                            }
                            AuthResult.QuickConnectExpired,
                            is AuthResult.Failed,
                            -> fail(WireErrors.AUTH_FAILED, reply)
                        }
                    }
                    QuickConnectState.Expired -> fail(WireErrors.EXPIRED, reply)

                    // The server went silent during polling: the Quick Connect request may
                    // still be alive; it is the path that vanished. The phone shows
                    // "server unreachable" and offers a retry.
                    QuickConnectState.Unavailable -> fail(WireErrors.UNREACHABLE, reply)
                }
            }
        } catch (e: CancellationException) {
            throw e // a reset is waiting for this cancellation: never swallow it
        } catch (_: Exception) {
            // Last line of defense. `appScope` is a bare `SupervisorJob` with no exception
            // handler: whatever escapes from here does not bubble up to `handleSetup`
            // (`join` rethrows nothing) but kills the watch's process, and the phone is left
            // with nothing but a timeout. So it will always answer something.
            //
            // The verdict depends on what has already happened: past authentication, the
            // session is persisted and the watch IS configured. It is then the announcement
            // that failed, not the provisioning — reporting failure would send the home
            // screen to an error while the watch is connected. The resend is a second
            // chance; the companion falls back on its own timeout if it fails too.
            val userName = configuredUserName
            val result = if (userName != null) {
                _state.value = RemoteSetupState.Done(userName)
                SetupResult(ok = true, userName = userName)
            } else {
                _state.value = RemoteSetupState.Failed(WireErrors.INTERNAL)
                SetupResult(ok = false, error = WireErrors.INTERNAL)
            }
            runCatching { reply(WirePaths.SETUP_RESULT, result.toWireBytes()) }
        } finally {
            // Failure, but also cancellation: the watch goes back to the certificate it was
            // working with. The operation is purely in-memory, hence safe here even when the
            // coroutine is already cancelled.
            if (!configured && pinStaged) pinRegistry.clearStaged()
        }
    }

    /**
     * Is the watch already on the requested server **and** account?
     *
     * The server identity takes precedence over the URL when both peers know it: the phone
     * and the watch may have validated two different canonical URLs of the same server, and
     * a URL comparison would then wrongly trigger a destructive reconfiguration. A phone
     * predating [SetupRequest.serverId] provides nothing: we fall back to the URL, and an
     * absent account constraint is deemed satisfied (original behavior).
     */
    private fun AuthSession.matches(request: SetupRequest): Boolean {
        val sameServer = if (serverId != null && request.serverId != null) {
            serverId == request.serverId
        } else {
            baseUrl.trimEnd('/').equals(request.serverUrl.trimEnd('/'), ignoreCase = true)
        }
        return sameServer && (request.userId == null || request.userId == userId)
    }

    private suspend fun fail(
        error: String,
        reply: suspend (path: String, payload: ByteArray) -> Unit,
    ) {
        reply(WirePaths.SETUP_RESULT, SetupResult(ok = false, error = error).toWireBytes())
        _state.value = RemoteSetupState.Failed(error)
    }
}
