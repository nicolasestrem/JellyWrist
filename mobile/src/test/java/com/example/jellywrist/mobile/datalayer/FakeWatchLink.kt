package com.example.jellywrist.mobile.datalayer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * Fake transport reproducing the property that causes races in production:
 * [incoming] is **hot and without replay**, so a message emitted before subscription is lost.
 */
class FakeWatchLink(
    node: String? = NODE,
    /**
     * Outcome returned by [discoverWatch]. Derived from [node] by default so the pre-existing
     * `FakeWatchLink(node = null)` call sites keep meaning "no watch is connected to this phone".
     *
     * A `var` so a test can reproduce the sequence this fake exists for: the phone reports the
     * app missing, the user installs it, and the *next* discovery finds it.
     */
    var discovery: WatchDiscovery = node
        ?.let { WatchDiscovery.Found(it, WATCH_NAME) }
        ?: WatchDiscovery.NoWatchConnected,
) : WatchLink {
    private val messages = MutableSharedFlow<WatchMessage>()

    /** Transport failure of the discovery step itself (Play Services `ApiException` in production). */
    var discoveryFailure: Exception? = null

    /** Paths sent to the watch, in order. */
    val sentPaths = mutableListOf<String>()
    val sentPayloads = mutableListOf<ByteArray>()

    /** Simulated watch reaction, invoked on each send. */
    var onSend: (suspend (path: String) -> Unit)? = null

    /**
     * Subscription log for [incoming]: `"+"` on open, `"-"` on close.
     *
     * Allows asserting that an exchange keeps **a single** listener from start to finish —
     * the invariant that a per-phase subscription would violate.
     */
    val subscriptionLog = mutableListOf<String>()

    /** Transport events in order: `"listen"` for [ensureListening], `"send:<path>"` for [send]. */
    val events = mutableListOf<String>()

    override suspend fun discoverWatch(): WatchDiscovery {
        discoveryFailure?.let { throw it }
        return discovery
    }

    override suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        sentPaths += path
        sentPayloads += payload
        events += "send:$path"
        onSend?.invoke(path)
    }

    override suspend fun ensureListening() {
        events += "listen"
    }

    override fun incoming(): Flow<WatchMessage> = messages
        .onStart { subscriptionLog += "+" }
        .onCompletion { subscriptionLog += "-" }

    /** Emits a watch reply after ensuring a subscriber exists. */
    suspend fun reply(path: String, payload: ByteArray) {
        messages.subscriptionCount.first { it > 0 }
        messages.emit(WatchMessage(NODE, path, payload))
    }

    companion object {
        const val NODE = "watch-node"

        /** Visibly synthetic display name — never a real device identifier. */
        const val WATCH_NAME = "Test Watch"
    }
}
