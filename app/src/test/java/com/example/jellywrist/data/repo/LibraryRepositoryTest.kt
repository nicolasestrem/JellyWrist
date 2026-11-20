package com.example.jellywrist.data.repo

import com.example.jellywrist.data.db.CollectionEntity
import com.example.jellywrist.data.db.CollectionType
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import com.example.jellywrist.support.FakeCollectionDao
import com.example.jellywrist.support.FakeCollectionTrackDao
import com.example.jellywrist.support.FakeTrackDao
import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.auth.SessionLoadResult
import com.example.jellywrist.data.auth.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [LibraryRepository]: SDK → Room mapping, local state preservation across
 * refreshes, pagination, and network resilience (PLAN.md §8 tasks 4-5, REQ-002/REQ-011).
 *
 * MockWebServer + fake [JellyfinBackend] built via `OkHttpFactory` (pattern
 * ServerRepositoryTest — the Android variant of the SDK would require a Context). JSON bodies
 * are in PascalCase, matching the SDK's generated `@SerialName`s.
 */
class LibraryRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var collectionDao: FakeCollectionDao
    private lateinit var trackDao: FakeTrackDao
    private lateinit var collectionTrackDao: FakeCollectionTrackDao
    private lateinit var repo: LibraryRepository

    private val userUuid = "11111111-2222-3333-4444-555555555555"
    private val collUuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val trackUuid1 = "aaaaaaaa-0000-0000-0000-000000000001"
    private val trackUuid2 = "aaaaaaaa-0000-0000-0000-000000000002"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        collectionDao = FakeCollectionDao()
        trackDao = FakeTrackDao()
        collectionTrackDao = FakeCollectionTrackDao(collectionDao, trackDao)

        val session = AuthSession(
            baseUrl = server.url("/").toString().trimEnd('/'),
            accessToken = "token",
            userId = userUuid,
            userName = "test",
            serverId = null,
        )
        val tokenStore = object : TokenStore {
            override fun save(s: AuthSession) = Unit

            override fun load(): SessionLoadResult = SessionLoadResult.Restored(session)

            override fun clear() = Unit
        }
        repo = LibraryRepository(
            authRepository = AuthRepository(OkHttpBackend(), tokenStore),
            backend = OkHttpBackend(),
            collectionDao = collectionDao,
            trackDao = trackDao,
            collectionTrackDao = collectionTrackDao,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Collections ─────────────────────────────────────────────────────

    @Test
    fun `refreshCollections maps Jellyfin fields`() = runTest {
        server.dispatcher = pageDispatcher(
            itemsJson = """[${playlistJson(collUuid, "Chill", sortName = "chill", imageTag = "tag1", childCount = 42)}]""",
        )

        val result = repo.refreshCollections(CollectionType.PLAYLIST)

        assertTrue(result.isSuccess)
        val entity = collectionDao.rows[collUuid]!!
        assertEquals("Chill", entity.name)
        assertEquals("chill", entity.sortName)
        assertEquals("tag1", entity.imageTag)
        assertEquals(42, entity.trackCount)
        assertEquals(CollectionType.PLAYLIST, entity.type)
    }

    @Test
    fun `pinning and sync state survive a refresh`() = runTest {
        collectionDao.upsertAll(
            listOf(
                CollectionEntity(
                    id = collUuid,
                    type = CollectionType.PLAYLIST,
                    name = "Old name",
                    pinned = true,
                    lastSyncAtEpochMs = 123L,
                    reconWatermark = "w1",
                    estimatedBytes = 999L,
                ),
            ),
        )
        server.dispatcher = pageDispatcher(
            itemsJson = """[${playlistJson(collUuid, "New name")}]""",
        )

        repo.refreshCollections(CollectionType.PLAYLIST)

        val entity = collectionDao.rows[collUuid]!!
        assertEquals("a metadata refresh never unpins", true, entity.pinned)
        assertEquals(123L, entity.lastSyncAtEpochMs)
        assertEquals("w1", entity.reconWatermark)
        assertEquals("New name", entity.name)
    }

    @Test
    fun `pagination loops to exhaustion`() = runTest {
        // 2 pages: the first full (PAGE_SIZE=200 items), the second partial.
        val page1 = (1..200).joinToString(",") { i ->
            playlistJson(uuidOf(i), "P$i")
        }
        val page2 = playlistJson(uuidOf(201), "P201")

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = request.requestUrl?.queryParameter("startIndex")?.toInt() ?: 0
                val body = if (start == 0) page1 else page2
                return jsonResponse("""{"Items":[$body],"TotalRecordCount":201,"StartIndex":$start}""")
            }
        }

        repo.refreshCollections(CollectionType.PLAYLIST)

        assertEquals("the 201 elements from both pages are cached", 201, collectionDao.rows.size)
    }

    @Test
    fun `a network failure leaves the cache intact and returns failure`() = runTest {
        collectionDao.upsertAll(
            listOf(CollectionEntity(id = collUuid, type = CollectionType.PLAYLIST, name = "Intact")),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }

        val result = repo.refreshCollections(CollectionType.PLAYLIST)

        assertTrue(result.isFailure)
        assertEquals("Intact", collectionDao.rows[collUuid]?.name)
    }

    // ── Tracks ──────────────────────────────────────────────────────────

    @Test
    fun `refreshTracks maps codec size and duration from MediaSources`() = runTest {
        pinnedAlbum()
        server.dispatcher = pageDispatcher(
            itemsJson = """[${audioJson(trackUuid1, "Track", codec = "flac", size = 12345, ticks = 30_000_000_000L)}]""",
        )

        val result = repo.refreshTracks(collUuid)

        assertTrue("${result.exceptionOrNull()}", result.isSuccess)
        val track = trackDao.rows[trackUuid1]!!
        assertEquals("flac", track.codec)
        assertEquals(12_345L, track.sizeBytes)
        // 30,000,000,000 ticks of 100 ns = 3,000,000 ms.
        assertEquals(3_000_000L, track.durationMs)
        assertEquals(TrackState.TO_DOWNLOAD, track.state)
    }

    @Test
    fun `local download state is never overwritten by a refresh`() = runTest {
        pinnedAlbum()
        trackDao.upsertAll(
            listOf(
                TrackEntity(
                    id = trackUuid1,
                    title = "Old",
                    state = TrackState.READY,
                    downloadedBytes = 777L,
                    errorMessage = null,
                ),
            ),
        )
        server.dispatcher = pageDispatcher(
            itemsJson = """[${audioJson(trackUuid1, "New", codec = "flac", size = 1, ticks = 0)}]""",
        )

        repo.refreshTracks(collUuid)

        val track = trackDao.rows[trackUuid1]!!
        assertEquals("READY never regresses (mass re-download)", TrackState.READY, track.state)
        assertEquals(777L, track.downloadedBytes)
        assertEquals("server metadata, however, does refresh", "New", track.title)
    }

    @Test
    fun `refreshTracks replaces links and updates estimated size`() = runTest {
        pinnedAlbum()
        server.dispatcher = pageDispatcher(
            itemsJson = """[
                ${audioJson(trackUuid1, "A", codec = "flac", size = 100, ticks = 0)},
                ${audioJson(trackUuid2, "B", codec = "alac", size = 50, ticks = 0)}
            ]""",
        )

        repo.refreshTracks(collUuid)

        assertEquals(listOf(trackUuid1, trackUuid2), collectionTrackDao.trackIds(collUuid))
        assertEquals(150L, collectionDao.rows[collUuid]?.estimatedBytes)
        assertEquals(2, collectionDao.rows[collUuid]?.trackCount)
    }

    @Test
    fun `observeTracks is cache-first, the cache responds without network`() = runTest {
        // No dispatcher: every network request would fail. The Room flow, however, responds.
        pinnedAlbum()
        trackDao.upsertAll(listOf(TrackEntity(id = trackUuid1, title = "Offline")))
        collectionTrackDao.insertAll(
            listOf(com.example.jellywrist.data.db.CollectionTrackEntity(collUuid, trackUuid1, 0)),
        )

        val tracks = repo.observeTracks(collUuid).first()

        assertEquals(listOf("Offline"), tracks.map { it.title })
    }

    // ── Local-only collections ──────────────────────────────────────────

    @Test
    fun `refreshTracks on a local-only collection succeeds without contacting the server`() = runTest {
        // The "random songs" collection is built on the watch and has no server
        // counterpart, so its id is not a Jellyfin UUID. Passing it to the SDK threw
        // "Invalid UUID string: local-random-songs" straight into the detail screen.
        collectionDao.upsertAll(
            listOf(
                CollectionEntity(
                    id = LibraryRepository.RANDOM_COLLECTION_ID,
                    type = CollectionType.PLAYLIST,
                    name = "50 random songs",
                    pinned = true,
                    localOnly = true,
                ),
            ),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }

        val result = repo.refreshTracks(LibraryRepository.RANDOM_COLLECTION_ID)

        assertTrue(result.exceptionOrNull()?.toString().orEmpty(), result.isSuccess)
        assertEquals("no request is sent for a local-only collection", 0, server.requestCount)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private suspend fun pinnedAlbum() {
        collectionDao.upsertAll(
            listOf(CollectionEntity(id = collUuid, type = CollectionType.ALBUM, name = "Album", pinned = true)),
        )
    }

    private fun uuidOf(i: Int) = "%08x-0000-0000-0000-000000000000".format(i)

    private fun pageDispatcher(itemsJson: String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            jsonResponse("""{"Items":$itemsJson,"TotalRecordCount":1,"StartIndex":0}""")
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun playlistJson(
        id: String,
        name: String,
        sortName: String? = null,
        imageTag: String? = null,
        childCount: Int? = null,
    ): String = buildString {
        append("""{"Id":"$id","Type":"Playlist","Name":"$name"""")
        if (sortName != null) append(""","SortName":"$sortName"""")
        if (imageTag != null) append(""","ImageTags":{"Primary":"$imageTag"}""")
        if (childCount != null) append(""","ChildCount":$childCount""")
        append("}")
    }

    private fun audioJson(id: String, name: String, codec: String, size: Long, ticks: Long): String = """
        {
          "Id": "$id",
          "Type": "Audio",
          "Name": "$name",
          "RunTimeTicks": $ticks,
          "MediaSources": [
            {
              "Protocol": "File",
              "Type": "Default",
              "Container": "$codec",
              "Size": $size,
              "IsRemote": false,
              "ReadAtNativeFramerate": false,
              "IgnoreDts": false,
              "IgnoreIndex": false,
              "GenPtsInput": false,
              "SupportsTranscoding": false,
              "SupportsDirectStream": true,
              "SupportsDirectPlay": true,
              "IsInfiniteStream": false,
              "RequiresOpening": false,
              "RequiresClosing": false,
              "RequiresLooping": false,
              "SupportsProbing": false,
              "TranscodingSubProtocol": "http",
              "HasSegments": false,
              "MediaStreams": [
                {
                  "Type": "Audio",
                  "Codec": "$codec",
                  "Index": 0,
                  "IsInterlaced": false,
                  "IsDefault": true,
                  "IsForced": false,
                  "IsHearingImpaired": false,
                  "IsExternal": false,
                  "IsTextSubtitleStream": false,
                  "SupportsExternalStream": false
                }
              ]
            }
          ]
        }
    """.trimIndent()

    /** Fake JVM port of the SDK — same pattern as ServerRepositoryTest. */
    private inner class OkHttpBackend : JellyfinBackend {
        private val factory = OkHttpFactory()

        override fun discoverServers(): Flow<ServerDiscoveryInfo> = emptyFlow()

        override fun apiClient(baseUrl: String, accessToken: String?): ApiClient = factory.create(
            baseUrl = server.url("/").toString().trimEnd('/'),
            accessToken = accessToken,
            clientInfo = ClientInfo("JellyWrist-test", "0.1.0"),
            deviceInfo = DeviceInfo("test-device", "test"),
            httpClientOptions = HttpClientOptions(),
            socketConnectionFactory = factory,
        )
    }
}
