package com.example.jellywrist.mobile.datalayer

import android.content.Context
import com.example.jellywrist.wire.WirePaths
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Message received from the watch.
 *
 * Deliberately distinct from `MessageEvent` (Play Services): the calling layer becomes
 * testable on JVM, where Wearable types are not instantiable. A simple class, not a
 * `data class` — [data] is an array, whose structural equality would be misleading.
 */
class WatchMessage(
    val sourceNodeId: String,
    val path: String,
    val data: ByteArray,
)

/**
 * Outcome of looking for a watch running JellyWrist.
 *
 * A single nullable node id could not distinguish "no watch is reachable from this phone"
 * from "a watch is connected but does not run JellyWrist", so both rendered the same dead-end
 * advice ("check Bluetooth pairing"). Each variant now carries the evidence the UI needs to
 * say what the user must actually do.
 */
sealed interface WatchDiscovery {
    /** A reachable node advertising [WirePaths.CAPABILITY_WATCH]. */
    data class Found(val nodeId: String, val displayName: String) : WatchDiscovery

    /**
     * No Wear OS node is connected to this phone right now.
     *
     * This is deliberately *not* "no watch is paired": `NodeClient.getConnectedNodes()` is a
     * live connection list, not a pairing registry, so a watch that is powered off, asleep,
     * or out of Bluetooth range lands here exactly like a handset that never had one. The
     * Data Layer exposes no way to tell those apart, so neither may the UI.
     */
    data object NoWatchConnected : WatchDiscovery

    /**
     * Nodes are connected but none advertises the capability: JellyWrist is missing on them.
     *
     * The capability is declared statically in the watch's `wear.xml`, so it is published as
     * soon as the app is installed — telling the user to "open JellyWrist on the watch"
     * would be wrong advice.
     */
    data class NodesWithoutApp(val names: List<String>) : WatchDiscovery
}

/**
 * Phone-to-watch transport for the Wearable Data Layer.
 *
 * Abstracted as an interface for the same reason as [com.example.jellywrist.data.jellyfin.JellyfinBackend]:
 * the orchestrations that depend on it ([WatchSetupController], [WatchStatusRepository]) carry
 * delicate subscription races, only testable with a fakeable transport.
 *
 * **[incoming] does not replay**: any subscription started after a message is emitted
 * loses it permanently. Callers must subscribe *before* sending the associated request,
 * and must call [ensureListening] first — it is the only way to guarantee the underlying
 * `MessageClient` listener is registered before the watch can possibly reply.
 */
interface WatchLink {
    /** Diagnoses the phone↔watch link: see [WatchDiscovery]. */
    suspend fun discoverWatch(): WatchDiscovery

    /** Watch node running JellyWrist, or `null` if none is reachable. */
    suspend fun findWatchNode(): String? = (discoverWatch() as? WatchDiscovery.Found)?.nodeId

    suspend fun send(nodeId: String, path: String, payload: ByteArray = ByteArray(0))

    /**
     * Suspends until the transport listener is registered. Idempotent and cheap once done.
     *
     * The previous design registered a listener inside `callbackFlow` per collection:
     * registration happened on the flow's own producer coroutine, so a `CoroutineStart.
     * UNDISPATCHED` collector gave **no** ordering guarantee against a subsequent `send` —
     * a fast watch reply could race the registration IPC and be lost.
     */
    suspend fun ensureListening()

    /** Messages received as long as the caller collects them — hot flow, no replay. */
    fun incoming(): Flow<WatchMessage>
}

/**
 * Suspending adapter around the phone's Wearable Data Layer.
 *
 * One long-lived `MessageClient` listener feeds a buffered [MutableSharedFlow]; per-request
 * register/unregister churn is gone, and a late reply (e.g. a `SETUP_RESULT` after the
 * caller's timeout) is still delivered to whoever is collecting instead of being dropped
 * by an already-closed `callbackFlow`.
 */
@Singleton
class WearableWatchLink @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WatchLink {
    /**
     * Buffered so a burst of replies never blocks the Play Services callback thread;
     * with no collector, `tryEmit` drops the message — same semantics as before
     * (no replay), minus the registration race.
     */
    private val messages = MutableSharedFlow<WatchMessage>(extraBufferCapacity = 64)

    private val listener = MessageClient.OnMessageReceivedListener { event ->
        messages.tryEmit(WatchMessage(event.sourceNodeId, event.path, event.data))
    }

    private val registration = Mutex()

    @Volatile
    private var listening = false

    /**
     * Combines the node list with the capability query.
     *
     * `CapabilityClient` alone only ever answers "some capable node / none", which is why the
     * phone could not tell a disconnected watch from one that lacks the app. The node list
     * supplies that missing half of the diagnosis — but only the connected half: neither
     * client reports pairings, so [WatchDiscovery.NoWatchConnected] stays agnostic about them.
     */
    override suspend fun discoverWatch(): WatchDiscovery {
        val connected = Wearable.getNodeClient(context).connectedNodes.await()
        val capable = Wearable.getCapabilityClient(context)
            .getCapability(WirePaths.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
        // A nearby node is preferred: it is reachable over Bluetooth rather than via the cloud.
        val match = capable.firstOrNull { it.isNearby } ?: capable.firstOrNull()
        return when {
            match != null -> WatchDiscovery.Found(match.id, match.displayName)
            connected.isEmpty() -> WatchDiscovery.NoWatchConnected
            else -> WatchDiscovery.NodesWithoutApp(connected.map(Node::getDisplayName))
        }
    }

    override suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        Wearable.getMessageClient(context).sendMessage(nodeId, path, payload).await()
    }

    override suspend fun ensureListening() {
        if (listening) return
        registration.withLock {
            if (listening) return
            Wearable.getMessageClient(context).addListener(listener).await()
            listening = true
        }
    }

    override fun incoming(): Flow<WatchMessage> = messages.asSharedFlow()
}
