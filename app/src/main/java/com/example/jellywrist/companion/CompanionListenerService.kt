package com.example.jellywrist.companion

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Receives commands from the phone companion via the Wearable Data Layer.
 *
 * Thin transport shell: all command logic lives in [CompanionCommandHandler] (JVM-testable,
 * survives this service being destroyed by the framework mid-exchange).
 */
@AndroidEntryPoint
class CompanionListenerService : WearableListenerService() {

    @Inject lateinit var handler: CompanionCommandHandler
    @Inject lateinit var appScope: CoroutineScope

    override fun onMessageReceived(event: MessageEvent) {
        super.onMessageReceived(event)
        val path = event.path
        val sourceNodeId = event.sourceNodeId
        val data = event.data
        appScope.launch {
            handler.handle(path, sourceNodeId, data, ::send)
        }
    }

    /**
     * Transport errors are harmless: the phone enforces its own timeout.
     *
     * The client is obtained from the **application context**, never from the service:
     * `onMessageReceived` returns well before the Quick Connect polling finishes, and Android
     * may destroy the service in the meantime — a reply sent through a dead service context
     * would fail silently (the phone would see a timeout instead of the result).
     */
    private suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        try {
            Wearable.getMessageClient(applicationContext).sendMessage(nodeId, path, payload).await()
        } catch (e: CancellationException) {
            // `runCatching` catches `Throwable` and therefore used to swallow cancellation:
            // `reply(...)` no longer threw anything, and provisioning kept going — `Done`
            // published, `SETUP_RESULT ok=true` emitted — for a setup the sign-out had just
            // cancelled. The reset happened to repair the state because it joins before
            // writing `Idle`; relying on that ordering would be fragile.
            throw e
        } catch (e: Exception) {
            // Harmless for the protocol (the phone times out and retries), but never silent:
            // an oversize payload or a dead transport must be visible in logcat.
            Log.w(TAG, "Failed to send $path to $nodeId", e)
        }
    }

    private companion object {
        const val TAG = "CompanionListener"
    }
}
