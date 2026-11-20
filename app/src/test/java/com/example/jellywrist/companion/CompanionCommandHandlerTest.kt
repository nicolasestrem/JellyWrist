package com.example.jellywrist.companion

import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.db.CollectionEntity
import com.example.jellywrist.data.db.CollectionType
import com.example.jellywrist.data.db.ServerDao
import com.example.jellywrist.data.db.ServerEntity
import com.example.jellywrist.data.net.PinRegistry
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.repo.LibraryRepository
import com.example.jellywrist.data.repo.ServerRepository
import com.example.jellywrist.data.reset.ServerDataReset
import com.example.jellywrist.data.storage.DeviceStorage
import com.example.jellywrist.download.DownloadProgress
import com.example.jellywrist.download.TrackDownloader
import com.example.jellywrist.support.FakeCollectionDao
import com.example.jellywrist.support.FakeCollectionTrackDao
import com.example.jellywrist.support.FakeSettingsRepository
import com.example.jellywrist.support.FakeSyncLogDao
import com.example.jellywrist.support.FakeTokenStore
import com.example.jellywrist.support.FakeTrackDao
import com.example.jellywrist.support.OkHttpJellyfinAuthBackend
import com.example.jellywrist.sync.PeriodicSyncArmer
import com.example.jellywrist.sync.SyncControl
import com.example.jellywrist.wire.PinCommand
import com.example.jellywrist.wire.SyncCommand
import com.example.jellywrist.wire.WatchStatus
import com.example.jellywrist.wire.WireAck
import com.example.jellywrist.wire.WireErrors
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.fromWire
import com.example.jellywrist.wire.toWireBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of the companion command handling extracted from
 * [CompanionListenerService] (GAP-011): failure isolation, command serialization,
 * insert-without-clobber, and the status payload size cap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionCommandHandlerTest {

    private val tokenStore = FakeTokenStore()
    private val collectionDao = FakeCollectionDao()
    private val trackDao = FakeTrackDao()
    private val syncLogDao = FakeSyncLogDao()
    private val settingsRepository = FakeSettingsRepository()
    private val syncControl = RecordingSyncControl()
    private var usedBytesProvider: suspend () -> Long = { 0L }

    /** Replies captured as (path, payload). */
    private val replies = mutableListOf<Pair<String, ByteArray>>()
    private val reply: WireReply = { _, path, payload -> replies += path to payload }

    private class RecordingSyncControl : SyncControl {
        val calls = mutableListOf<String>()
        val meteredCalls = mutableListOf<Boolean>()
        val lowBatteryCalls = mutableListOf<Boolean>()
        var onScheduleAutomaticOneShot: suspend () -> Unit = {}

        override suspend fun syncNow(allowMetered: Boolean, allowLowBattery: Boolean) {
            calls += "syncNow"
            meteredCalls += allowMetered
            lowBatteryCalls += allowLowBattery
        }

        override suspend fun scheduleAutomaticOneShot() {
            calls += "scheduleAutomaticOneShot"
            onScheduleAutomaticOneShot()
        }
    }

    private fun handler(scope: kotlinx.coroutines.CoroutineScope): CompanionCommandHandler {
        val backend = OkHttpJellyfinAuthBackend()
        val authRepository = AuthRepository(backend, tokenStore)
        return CompanionCommandHandler(
            coordinator = CompanionSetupCoordinator(
                serverRepository = ServerRepository(backend),
                authRepository = authRepository,
                settingsRepository = settingsRepository,
                pinRegistry = PinRegistry(),
                serverDataReset = ServerDataReset {},
                periodicSyncArmer = PeriodicSyncArmer {},
                appScope = scope,
            ),
            authRepository = authRepository,
            libraryRepository = LibraryRepository(
                authRepository = authRepository,
                backend = backend,
                collectionDao = collectionDao,
                trackDao = trackDao,
                collectionTrackDao = FakeCollectionTrackDao(collectionDao, trackDao),
            ),
            collectionDao = collectionDao,
            syncControl = syncControl,
            settingsRepository = settingsRepository,
            deviceStorage = object : DeviceStorage {
                override suspend fun availableBytes(): Long = 5_000L
                override suspend fun totalBytes(): Long = 10_000L
            },
            trackDownloader = object : TrackDownloader {
                override suspend fun enqueue(trackIds: List<String>) = Unit
                override suspend fun remove(trackIds: List<String>) = Unit
                override fun observeProgress(): Flow<List<DownloadProgress>> = emptyFlow()
                override suspend fun usedBytes(): Long = usedBytesProvider()

                override suspend fun hasOutstandingWork(): Boolean = false
                override suspend fun pauseAll() = Unit
                override suspend fun resumeAll() = Unit
            },
            syncLogDao = syncLogDao,
            serverDao = object : ServerDao {
                override suspend fun upsert(server: ServerEntity) = Unit
                override suspend fun byId(id: String): ServerEntity? = null
                override fun observeCurrent(): Flow<ServerEntity?> =
                    kotlinx.coroutines.flow.flowOf(null)
                override suspend fun clear() = Unit
            },
        )
    }

    private fun signIn() {
        // Unreachable base URL: the pin path's best-effort track refresh fails fast and is
        // swallowed by LibraryRepository — no network fixture needed.
        tokenStore.save(
            AuthSession(
                baseUrl = "http://localhost:9",
                accessToken = "token",
                userId = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                userName = "Avery",
                serverId = "server-1",
            ),
        )
    }

    @Test
    fun `a command without session is refused with NO_SESSION`() = runTest {
        val handler = handler(backgroundScope)

        handler.handle(WirePaths.SYNC_NOW, NODE, ByteArray(0), reply)
        handler.handle(
            WirePaths.PIN,
            NODE,
            PinCommand("c1", "ALBUM", "Album", pin = true).toWireBytes(),
            reply,
        )

        assertEquals(listOf(WirePaths.SYNC_REPLY, WirePaths.PIN_REPLY), replies.map { it.first })
        replies.forEach { (_, payload) ->
            assertEquals(WireErrors.NO_SESSION, payload.fromWire<WireAck>().error)
        }
        assertEquals(emptyList<String>(), syncControl.calls)
    }

    @Test
    fun `sync now queues the work and acks`() = runTest {
        signIn()
        val handler = handler(backgroundScope)

        handler.handle(WirePaths.SYNC_NOW, NODE, ByteArray(0), reply)

        assertEquals(listOf("syncNow"), syncControl.calls)
        assertEquals(listOf(false), syncControl.meteredCalls)
        assertEquals(listOf(false), syncControl.lowBatteryCalls)
        assertTrue(replies.single().second.fromWire<WireAck>().ok)
    }

    @Test
    fun `sync command forwards explicit metered consent`() = runTest {
        signIn()
        val handler = handler(backgroundScope)

        handler.handle(WirePaths.SYNC_NOW, NODE, SyncCommand(true).toWireBytes(), reply)

        assertEquals(listOf(true), syncControl.meteredCalls)
        assertEquals(listOf(false), syncControl.lowBatteryCalls)
        assertTrue(replies.single().second.fromWire<WireAck>().ok)
    }

    @Test
    fun `sync command forwards explicit low battery consent`() = runTest {
        signIn()
        val handler = handler(backgroundScope)

        handler.handle(
            WirePaths.SYNC_NOW,
            NODE,
            SyncCommand(allowLowBattery = true).toWireBytes(),
            reply,
        )

        assertEquals(listOf(false), syncControl.meteredCalls)
        assertEquals(listOf(true), syncControl.lowBatteryCalls)
        assertTrue(replies.single().second.fromWire<WireAck>().ok)
    }

    @Test
    fun `malformed sync command is refused`() = runTest {
        signIn()
        val handler = handler(backgroundScope)

        handler.handle(WirePaths.SYNC_NOW, NODE, "bad".encodeToByteArray(), reply)

        assertTrue(syncControl.meteredCalls.isEmpty())
        assertEquals(WireErrors.BAD_COMMAND, replies.single().second.fromWire<WireAck>().error)
    }

    @Test
    fun `a handler failure replies INTERNAL instead of escaping to the appScope`() = runTest {
        signIn()
        usedBytesProvider = { error("cache exploded") }
        val handler = handler(backgroundScope)

        handler.handle(WirePaths.STATUS_REQUEST, NODE, ByteArray(0), reply)

        val (path, payload) = replies.single()
        assertEquals(WirePaths.STATUS_REPLY, path)
        val ack = payload.fromWire<WireAck>()
        assertEquals(false, ack.ok)
        assertEquals(WireErrors.INTERNAL, ack.error)
    }

    @Test
    fun `a pin does not clobber an existing collection row`() = runTest {
        signIn()
        collectionDao.upsertAll(
            listOf(
                CollectionEntity(
                    id = "c1",
                    type = CollectionType.ALBUM,
                    name = "Fresh name from server",
                    trackCount = 42,
                ),
            ),
        )
        val handler = handler(backgroundScope)

        handler.handle(
            WirePaths.PIN,
            NODE,
            PinCommand("c1", "ALBUM", "Stale wire name", trackCount = 1, pin = true).toWireBytes(),
            reply,
        )

        assertEquals("Fresh name from server", collectionDao.rows.getValue("c1").name)
        assertEquals(42, collectionDao.rows.getValue("c1").trackCount)
        assertTrue(collectionDao.rows.getValue("c1").pinned)
        assertEquals(listOf("scheduleAutomaticOneShot"), syncControl.calls)
        assertEquals(listOf(WirePaths.PIN_REPLY, WirePaths.STATUS_REPLY), replies.map { it.first })
    }

    @Test
    fun `concurrent pins are serialized`() = runTest {
        signIn()
        val handler = handler(backgroundScope)
        val firstPinBlocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstCall = true
        syncControl.onScheduleAutomaticOneShot = {
            if (firstCall) {
                firstCall = false
                firstPinBlocked.complete(Unit)
                release.await()
            }
        }

        val first = launch {
            handler.handle(
                WirePaths.PIN,
                NODE,
                PinCommand("c1", "ALBUM", "One", pin = true).toWireBytes(),
                reply,
            )
        }
        firstPinBlocked.await()
        val second = launch {
            handler.handle(
                WirePaths.PIN,
                NODE,
                PinCommand("c2", "ALBUM", "Two", pin = true).toWireBytes(),
                reply,
            )
        }
        // Give the second pin every chance to run — the mutex must hold it back.
        advanceUntilIdle()
        assertEquals(listOf(WirePaths.PIN_REPLY), replies.map { it.first })

        release.complete(Unit)
        first.join()
        second.join()

        // Full first exchange (ack + status), then the full second one — no interleaving.
        assertEquals(
            listOf(
                WirePaths.PIN_REPLY,
                WirePaths.STATUS_REPLY,
                WirePaths.PIN_REPLY,
                WirePaths.STATUS_REPLY,
            ),
            replies.map { it.first },
        )
    }

    @Test
    fun `an oversized status is capped below the transport limit`() = runTest {
        signIn()
        collectionDao.upsertAll(
            (1..2000).map { i ->
                CollectionEntity(
                    id = "collection-$i",
                    type = CollectionType.ALBUM,
                    name = "A reasonably long album name to inflate the payload $i",
                    trackCount = i,
                    pinned = true,
                )
            },
        )
        val handler = handler(backgroundScope)

        handler.handle(WirePaths.STATUS_REQUEST, NODE, ByteArray(0), reply)

        val (path, payload) = replies.single()
        assertEquals(WirePaths.STATUS_REPLY, path)
        assertTrue("payload must fit the MessageClient limit", payload.size <= 100_000)
        val status = payload.fromWire<WatchStatus>()
        assertTrue(status.collectionsTruncated)
        assertTrue(status.collections.isNotEmpty())
    }

    private companion object {
        const val NODE = "phone-node"
    }
}
