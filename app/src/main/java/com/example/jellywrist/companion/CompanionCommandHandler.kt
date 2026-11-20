package com.example.jellywrist.companion

import android.util.Log
import com.example.jellywrist.BuildConfig
import com.example.jellywrist.data.db.CollectionDao
import com.example.jellywrist.data.db.CollectionEntity
import com.example.jellywrist.data.db.CollectionType
import com.example.jellywrist.data.db.ServerDao
import com.example.jellywrist.data.db.SyncLogDao
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.repo.LibraryRepository
import com.example.jellywrist.data.settings.SettingsRepository
import com.example.jellywrist.data.storage.DeviceStorage
import com.example.jellywrist.download.TrackDownloader
import com.example.jellywrist.sync.SyncControl
import com.example.jellywrist.wire.PinCommand
import com.example.jellywrist.wire.SetupRequest
import com.example.jellywrist.wire.SyncCommand
import com.example.jellywrist.wire.WatchStatus
import com.example.jellywrist.wire.WireAck
import com.example.jellywrist.wire.WireErrors
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.WirePinnedCollection
import com.example.jellywrist.wire.fromWire
import com.example.jellywrist.wire.sizeCapped
import com.example.jellywrist.wire.toWireBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Transport abstraction: replies to a node without knowing about Play Services. */
typealias WireReply = suspend (nodeId: String, path: String, payload: ByteArray) -> Unit

/**
 * Handles the companion commands received by [CompanionListenerService], outside the service
 * so the logic is JVM-testable and survives the service being destroyed.
 *
 * PIN / SYNC_NOW / STATUS_REQUEST are serialized under [commandMutex]: two concurrent pins
 * used to interleave freely (check-then-act on the collection row, torn status snapshots).
 * SETUP is deliberately **not** under this mutex — provisioning can hold a Quick Connect
 * exchange open for minutes and has its own mutex in [CompanionSetupCoordinator]; a status
 * request must not queue behind it.
 *
 * Every handler is wrapped so an unexpected failure replies `WireAck(false, INTERNAL)`
 * instead of escaping to the appScope (GAP-011): the phone shows an error rather than
 * timing out against a dead process.
 */
@Singleton
class CompanionCommandHandler @Inject constructor(
    private val coordinator: CompanionSetupCoordinator,
    private val authRepository: AuthRepository,
    private val libraryRepository: LibraryRepository,
    private val collectionDao: CollectionDao,
    private val syncControl: SyncControl,
    private val settingsRepository: SettingsRepository,
    private val deviceStorage: DeviceStorage,
    private val trackDownloader: TrackDownloader,
    private val syncLogDao: SyncLogDao,
    private val serverDao: ServerDao,
) {
    private val commandMutex = Mutex()

    suspend fun handle(path: String, sourceNodeId: String, data: ByteArray, reply: WireReply) {
        when (path) {
            WirePaths.SETUP -> handleSetup(sourceNodeId, data, reply)
            WirePaths.STATUS_REQUEST -> guarded(sourceNodeId, WirePaths.STATUS_REPLY, reply) {
                commandMutex.withLock { sendStatus(sourceNodeId, reply) }
            }
            WirePaths.PIN -> guarded(sourceNodeId, WirePaths.PIN_REPLY, reply) {
                commandMutex.withLock { handlePin(sourceNodeId, data, reply) }
            }
            WirePaths.SYNC_NOW -> guarded(sourceNodeId, WirePaths.SYNC_REPLY, reply) {
                commandMutex.withLock { handleSyncNow(sourceNodeId, data, reply) }
            }
        }
    }

    /**
     * Replies `INTERNAL` on any unexpected failure. Cancellation passes through untouched —
     * a sign-out may be waiting on it.
     */
    private suspend fun guarded(
        nodeId: String,
        replyPath: String,
        reply: WireReply,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Companion command failed ($replyPath)", e)
            runCatching { reply(nodeId, replyPath, WireAck(false, WireErrors.INTERNAL).toWireBytes()) }
        }
    }

    private suspend fun handleSetup(sourceNodeId: String, data: ByteArray, reply: WireReply) {
        val request = runCatching { data.fromWire<SetupRequest>() }.getOrNull() ?: return
        coordinator.handleSetup(request) { path, payload -> reply(sourceNodeId, path, payload) }
    }

    private suspend fun handlePin(nodeId: String, data: ByteArray, reply: WireReply) {
        val command = runCatching { data.fromWire<PinCommand>() }.getOrNull()
        if (command == null) {
            reply(nodeId, WirePaths.PIN_REPLY, WireAck(false, WireErrors.BAD_COMMAND).toWireBytes())
            return
        }
        if (authRepository.currentSession() == null) {
            reply(nodeId, WirePaths.PIN_REPLY, WireAck(false, WireErrors.NO_SESSION).toWireBytes())
            return
        }

        if (command.pin) {
            val collectionType = runCatching { CollectionType.valueOf(command.collectionType) }.getOrNull()
            if (collectionType == null) {
                reply(nodeId, WirePaths.PIN_REPLY, WireAck(false, WireErrors.BAD_COMMAND).toWireBytes())
                return
            }
            collectionDao.insertIgnore(
                CollectionEntity(
                    id = command.collectionId,
                    type = collectionType,
                    name = command.name,
                    trackCount = command.trackCount,
                ),
            )
        }

        // The ack is immediate: refreshing the tracks can take several seconds.
        reply(nodeId, WirePaths.PIN_REPLY, WireAck(ok = true).toWireBytes())
        try {
            libraryRepository.setPinned(command.collectionId, command.pin)
            syncControl.scheduleAutomaticOneShot()
        } finally {
            sendStatus(nodeId, reply)
        }
    }

    private suspend fun handleSyncNow(nodeId: String, data: ByteArray, reply: WireReply) {
        val command = if (data.isEmpty()) {
            SyncCommand()
        } else {
            runCatching { data.fromWire<SyncCommand>() }.getOrNull()
        }
        if (command == null) {
            reply(nodeId, WirePaths.SYNC_REPLY, WireAck(false, WireErrors.BAD_COMMAND).toWireBytes())
            return
        }
        if (authRepository.currentSession() == null) {
            reply(nodeId, WirePaths.SYNC_REPLY, WireAck(false, WireErrors.NO_SESSION).toWireBytes())
            return
        }
        syncControl.syncNow(
            allowMetered = command.allowMetered,
            allowLowBattery = command.allowLowBattery,
        )
        // Means "queued", not "synced": the phone labels it accordingly.
        reply(nodeId, WirePaths.SYNC_REPLY, WireAck(ok = true).toWireBytes())
    }

    /** Sends the full snapshot only if a local session exists. */
    private suspend fun sendStatus(nodeId: String, reply: WireReply) {
        val session = authRepository.currentSession()
        if (session == null) {
            reply(nodeId, WirePaths.STATUS_REPLY, WatchStatus(configured = false).toWireBytes())
            return
        }

        val settings = settingsRepository.settings.first()
        val server = serverDao.observeCurrent().first()
        val pinned = collectionDao.observePinned().first()
        val latest = syncLogDao.observeLatest().first()
        val status = WatchStatus(
            configured = true,
            serverName = server?.name,
            serverUrl = session.baseUrl,
            userName = session.userName,
            usedBytes = trackDownloader.usedBytes(),
            quotaBytes = settings.quotaBytes,
            availableDiskBytes = deviceStorage.availableBytes(),
            collections = pinned.map {
                WirePinnedCollection(
                    id = it.id,
                    name = it.name,
                    type = it.type.name,
                    trackCount = it.trackCount,
                    estimatedBytes = it.estimatedBytes,
                    lastSyncAtEpochMs = it.lastSyncAtEpochMs,
                )
            },
            lastSyncOutcome = latest?.outcome?.name,
            lastSyncEpochMs = latest?.finishedAtEpochMs,
            appVersionName = BuildConfig.VERSION_NAME,
            syncState = syncControl.currentSyncState().name,
            syncBlocker = syncControl.currentSyncBlocker(),
        ).sizeCapped()
        reply(nodeId, WirePaths.STATUS_REPLY, status.toWireBytes())
    }

    private companion object {
        const val TAG = "CompanionCommands"
    }
}
