package com.example.jellywrist.mobile.datalayer

import com.example.jellywrist.wire.PinCommand
import com.example.jellywrist.wire.SyncCommand
import com.example.jellywrist.wire.WatchStatus
import com.example.jellywrist.wire.WireAck
import com.example.jellywrist.wire.WireErrors
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.fromWire
import com.example.jellywrist.wire.toWireBytes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Remote watch state, kept in memory only. */
@Singleton
class WatchStatusRepository @Inject constructor(
    private val watchLink: WatchLink,
) {
    private val _status = MutableStateFlow<WatchStatus?>(null)
    val status: StateFlow<WatchStatus?> = _status.asStateFlow()

    /**
     * Last discovery outcome, or `null` while none has completed (including when the
     * discovery call itself threw).
     *
     * Every remote command starts by locating the node, so this is refreshed on each of them
     * and is what lets the UI name the actual problem instead of printing one catch-all
     * sentence for four different causes.
     */
    private val _discovery = MutableStateFlow<WatchDiscovery?>(null)
    val discovery: StateFlow<WatchDiscovery?> = _discovery.asStateFlow()

    suspend fun refresh(): Result<Unit> = runCatching {
        val nodeId = findNode()
        val reply = request<WatchStatus>(nodeId, WirePaths.STATUS_REQUEST, WirePaths.STATUS_REPLY, ByteArray(0), 15.seconds)
        _status.value = reply
    }

    suspend fun pin(command: PinCommand): Result<WireAck> = runCatching {
        val nodeId = findNode()
        watchLink.ensureListening()
        coroutineScope {
            // Start listening for status BEFORE sending the pin: the watch may send
            // STATUS_REPLY immediately after PIN_REPLY (notably for an unpin, without
            // network refresh), and incoming() does not replay past messages.
            val statusJob = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    awaitMessage<WatchStatus>(nodeId, WirePaths.STATUS_REPLY, 10.seconds)
                }
            }
            val ack = request<WireAck>(
                nodeId,
                WirePaths.PIN,
                WirePaths.PIN_REPLY,
                command.toWireBytes(),
                15.seconds,
            )
            if (ack.ok) {
                statusJob.await().getOrNull()?.let { _status.value = it }
            } else {
                statusJob.cancel()
            }
            ack
        }
    }

    suspend fun syncNow(
        allowMetered: Boolean = false,
        allowLowBattery: Boolean = false,
    ): Result<WireAck> = runCatching {
        val nodeId = findNode()
        request<WireAck>(
            nodeId,
            WirePaths.SYNC_NOW,
            WirePaths.SYNC_REPLY,
            SyncCommand(
                allowMetered = allowMetered,
                allowLowBattery = allowLowBattery,
            ).toWireBytes(),
            15.seconds,
        )
    }

    /** Polls long enough to expose active, blocked, completed, or failed state. */
    suspend fun pollSyncStatus(previousLastSyncEpochMs: Long?): Result<Unit> = runCatching {
        repeat(SYNC_STATUS_POLL_ATTEMPTS) {
            refresh().getOrThrow()
            val current = _status.value
            val finished = current?.lastSyncEpochMs != null &&
                current.lastSyncEpochMs != previousLastSyncEpochMs
            if (current?.syncBlocker != null || current?.syncState == "FAILED" || finished) {
                return@runCatching
            }
            delay(SYNC_STATUS_POLL_INTERVAL_MS)
        }
    }

    /**
     * Looks for the watch and publishes *why* it was or was not found.
     *
     * The single writer of [discovery]. `WatchSetupController` runs its own discovery at the
     * start of provisioning and used to call the transport directly, so the answer that
     * actually drove the setup attempt never reached the screen: the home card kept whatever
     * diagnosis the last status refresh had produced, and told the user the app was still
     * missing from a watch that had just been found.
     */
    suspend fun discover(): WatchDiscovery {
        val outcome = runCatching { watchLink.discoverWatch() }
        _discovery.value = outcome.getOrNull()
        return outcome.getOrThrow()
    }

    /**
     * Locates the watch and records *why* it could not be found.
     *
     * The thrown [WireErrors.WATCH_UNREACHABLE] stays the transport-level signal every caller
     * already folds into an `OpState`; [discovery] carries the diagnosis alongside it.
     */
    private suspend fun findNode(): String {
        val found = discover() as? WatchDiscovery.Found ?: error(WireErrors.WATCH_UNREACHABLE)
        return found.nodeId
    }

    private suspend inline fun <reified T> request(
        nodeId: String,
        requestPath: String,
        replyPath: String,
        payload: ByteArray,
        timeout: Duration,
    ): T = coroutineScope {
        // Registration first (IPC — the part UNDISPATCHED could never guarantee), then the
        // collector: collecting a SharedFlow registers the subscriber synchronously before
        // suspending, so with UNDISPATCHED it is in place when send() runs.
        watchLink.ensureListening()
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            awaitMessage<T>(nodeId, replyPath, timeout)
        }
        watchLink.send(nodeId, requestPath, payload)
        reply.await()
    }

/** Ignores malformed packets: the timeout then triggers the UI retry mechanism. */
    private suspend inline fun <reified T> awaitMessage(nodeId: String, path: String, timeout: Duration): T =
        withTimeout(timeout) {
            watchLink.incoming()
                .filter { it.sourceNodeId == nodeId && it.path == path }
                .mapNotNull { runCatching { it.data.fromWire<T>() }.getOrNull() }
                .first()
        }

    private companion object {
        const val SYNC_STATUS_POLL_ATTEMPTS = 12
        const val SYNC_STATUS_POLL_INTERVAL_MS = 1_000L
    }
}
