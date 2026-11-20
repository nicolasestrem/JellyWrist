package com.example.jellywrist.mobile.datalayer

import android.util.Log
import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.mobile.data.MobilePrefs
import com.example.jellywrist.wire.SetupQcCode
import com.example.jellywrist.wire.SetupRequest
import com.example.jellywrist.wire.SetupResult
import com.example.jellywrist.wire.WireErrors
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.fromWire
import com.example.jellywrist.wire.toWireBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.operations.QuickConnectApi
import org.jellyfin.sdk.model.serializer.toUUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Visible states of watch provisioning without watch input. */
sealed interface WatchSetupState {
    data object Idle : WatchSetupState
    data object Searching : WatchSetupState
    data object Sending : WatchSetupState
    data object WaitingForWatchCode : WatchSetupState
    data class Approving(val code: String) : WatchSetupState
    data object WaitingForResult : WatchSetupState
    data class Success(val userName: String?, val alreadyConfigured: Boolean = false) : WatchSetupState
    data class Failure(val error: String) : WatchSetupState
}

/** First reply from the watch: a code to approve, or already a terminal result. */
internal sealed interface InitialSetupReply {
    data class QcCode(val code: String) : InitialSetupReply
    data class Result(val result: SetupResult) : InitialSetupReply
}

/**
 * **Single** subscription spanning the entire provisioning exchange.
 *
 * [WatchLink.incoming] is a hot flow without replay: a subscription opened then closed
 * between two phases loses any message emitted in the interval. The watch continuously
 * polls Quick Connect approval and may therefore emit `SETUP_RESULT` while the phone's
 * authorization call is still in flight. A single listener, started before sending `SETUP`
 * and maintained through completion, eliminates the race: the result is stored upon arrival
 * whether or not someone is already awaiting it.
 */
internal class SetupReplyListener(
    watchLink: WatchLink,
    nodeId: String,
    scope: CoroutineScope,
) {
    // Declaration order matters: `UNDISPATCHED` runs the collect body upon
    // construction, so both `CompletableDeferred` must be initialized before [job].
    private val firstReply = CompletableDeferred<InitialSetupReply>()
    private val terminalResult = CompletableDeferred<SetupResult>()

    private val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            watchLink.incoming()
                .filter { it.sourceNodeId == nodeId }
                .collect { message ->
                    // Unreadable packets are ignored: the wait timeout takes over and
                    // the UI offers a retry, rather than failing on a mis-paired peer.
                    when (message.path) {
                        WirePaths.SETUP_QC -> message.decodeOrNull<SetupQcCode>()?.let {
                            firstReply.complete(InitialSetupReply.QcCode(it.code))
                        }

                        WirePaths.SETUP_RESULT -> message.decodeOrNull<SetupResult>()?.let {
                            // `complete` is a no-op if phase 1 has already concluded.
                            firstReply.complete(InitialSetupReply.Result(it))
                            terminalResult.complete(it)
                        }
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Data Layer transport failure: the listener is a child of the provisioning
            // coroutine; letting it fail would cancel the entire exchange without showing
            // anything. The awaits then expire normally and the UI offers a retry.
        }
    }

/** First reply from the watch, or `null` beyond [timeout]. */
    suspend fun awaitFirstReply(timeout: Duration): InitialSetupReply? =
        withTimeoutOrNull(timeout) { firstReply.await() }

/** Terminal result — returned immediately if it has already arrived. */
    suspend fun awaitResult(timeout: Duration): SetupResult? =
        withTimeoutOrNull(timeout) { terminalResult.await() }

    fun cancel() {
        job.cancel()
    }
}

private inline fun <reified T> WatchMessage.decodeOrNull(): T? =
    runCatching { data.fromWire<T>() }.getOrNull()

/**
 * Marks a failure of the **discovery step itself**, so the catch-all can stay narrow.
 *
 * Without it, every exception raised anywhere in the exchange (a Jellyfin HTTP error, a
 * DataStore read, a serialization fault) was reported as `watch_unreachable`, sending the user
 * to check a Bluetooth pairing that was never the problem.
 */
private class WatchDiscoveryFailure(cause: Throwable) : Exception(cause)

/** Orchestrates self-approval of Quick Connect between the phone and the watch. */
@Singleton
class WatchSetupController @Inject constructor(
    private val watchLink: WatchLink,
    private val authRepository: AuthRepository,
    private val backend: JellyfinBackend,
    private val mobilePrefs: MobilePrefs,
    private val watchStatusRepository: WatchStatusRepository,
    private val appScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<WatchSetupState>(WatchSetupState.Idle)
    val state: StateFlow<WatchSetupState> = _state.asStateFlow()

    private var setupJob: Job? = null

    /**
     * Runs provisioning, returning the [Job] so a caller can await the outcome.
     *
     * The `Job` matters because [state] is a `StateFlow`: its current value may still be the
     * terminal state of an earlier attempt, so a caller that merely waited for `Success` or
     * `Failure` would return immediately with the previous run's answer.
     */
    fun startSetup(): Job {
        setupJob?.cancel()
        val job = appScope.launch {
            try {
                runSetup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: WatchDiscoveryFailure) {
                // Only the discovery step justifies pointing the user at the phone↔watch link.
                Log.w(TAG, "watch discovery failed: ${e.cause?.javaClass?.name}")
                _state.value = WatchSetupState.Failure(WireErrors.WATCH_UNREACHABLE)
            } catch (e: Exception) {
                // `appScope` has no exception handler — letting this propagate would kill the
                // process instead of showing the failure. Only the exception *class* is logged:
                // a Jellyfin response message or a URL would leak the server and the account.
                Log.w(TAG, "watch setup failed: ${e.javaClass.name}")
                _state.value = WatchSetupState.Failure(WireErrors.INTERNAL)
            }
        }
        setupJob = job
        return job
    }

    private suspend fun CoroutineScope.runSetup() {
        val session = authRepository.currentSession()
        if (session == null) {
            _state.value = WatchSetupState.Failure(WireErrors.NO_SESSION)
            return
        }
        _state.value = WatchSetupState.Searching
        val discovery = try {
            // Through the repository, not the transport: this is the discovery the attempt
            // actually used, and the home card reads its diagnosis from `discovery`.
            watchStatusRepository.discover()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WatchDiscoveryFailure(e)
        }
        val nodeId = (discovery as? WatchDiscovery.Found)?.nodeId
        if (nodeId == null) {
            // The home screen names the exact cause from `WatchStatusRepository.discovery`;
            // this state only has to stay non-terminal-silent.
            _state.value = WatchSetupState.Failure(WireErrors.WATCH_UNREACHABLE)
            return
        }
        _state.value = WatchSetupState.Sending
        // Server and account identity accompany the URL: without them, a watch that is
        // already configured would acknowledge "already configured" even when the phone has
        // changed servers — the "Reconfigure" button would then have no effect.
        val payload = SetupRequest(
            serverUrl = session.baseUrl,
            pinnedCertSha256 = mobilePrefs.pinnedCertificateSha256.first(),
            serverId = session.serverId,
            userId = session.userId,
        ).toWireBytes()

        // Subscribe BEFORE sending: the watch may reply SETUP_QC (code to approve) or
        // SETUP_RESULT immediately (already configured, invalid URL, QC unavailable).
        // ensureListening() guarantees the transport listener registration (IPC) has
        // completed — the UNDISPATCHED collector alone never could.
        watchLink.ensureListening()
        val replies = SetupReplyListener(watchLink, nodeId, this)
        try {
            watchLink.send(nodeId, WirePaths.SETUP, payload)
            _state.value = WatchSetupState.WaitingForWatchCode
            when (val initialReply = replies.awaitFirstReply(FIRST_REPLY_TIMEOUT)) {
                null -> {
                    _state.value = WatchSetupState.Failure(WireErrors.TIMEOUT)
                    return
                }

                is InitialSetupReply.Result -> {
                    publish(initialReply.result)
                    return
                }

                is InitialSetupReply.QcCode ->
                    if (!authorize(initialReply.code, session)) return
            }
            _state.value = WatchSetupState.WaitingForResult
            val result = replies.awaitResult(RESULT_TIMEOUT)
            if (result == null) {
                _state.value = WatchSetupState.Failure(WireErrors.TIMEOUT)
                return
            }
            publish(result)
        } finally {
            replies.cancel()
        }
    }

/** Approves the code on the server; the result listener stays open during the call. */
    private suspend fun authorize(
        code: String,
        session: AuthSession,
    ): Boolean {
        _state.value = WatchSetupState.Approving(code)
        val approved = try {
            QuickConnectApi(backend.apiClient(session.baseUrl, session.accessToken))
                .authorizeQuickConnect(code, session.userId.toUUID())
                .content
        } catch (e: CancellationException) {
            throw e // a second request cancels the first: do not publish a failure
        } catch (_: Exception) {
            false
        }
        if (!approved) _state.value = WatchSetupState.Failure(WireErrors.AUTH_FAILED)
        return approved
    }

    /**
     * Publishes the outcome and re-syncs the status: without this, the home screen would
     * stay on `configured = false` ("browse"/"sync" controls hidden) until a manual
     * refresh.
     */
    private suspend fun publish(result: SetupResult) {
        if (!result.ok) {
            _state.value = WatchSetupState.Failure(result.error ?: WireErrors.AUTH_FAILED)
            return
        }
        _state.value = WatchSetupState.Success(result.userName, result.alreadyConfigured)
        watchStatusRepository.refresh()
    }

    private companion object {
        const val TAG = "WatchSetupController"

/** The watch replies upon receipt; beyond that, it is out of range or busy. */
        val FIRST_REPLY_TIMEOUT = 30.seconds

/** Covers server approval then the Quick Connect secret exchange. */
        val RESULT_TIMEOUT = 90.seconds
    }
}
