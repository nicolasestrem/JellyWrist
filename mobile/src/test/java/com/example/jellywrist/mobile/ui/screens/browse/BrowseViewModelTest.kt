package com.example.jellywrist.mobile.ui.screens.browse

import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.mobile.InMemoryTokenStore
import com.example.jellywrist.mobile.TestBackend
import com.example.jellywrist.mobile.testSession
import com.example.jellywrist.mobile.data.CatalogItem
import com.example.jellywrist.mobile.data.CatalogType
import com.example.jellywrist.mobile.data.PhoneCatalogRepository
import com.example.jellywrist.mobile.datalayer.FakeWatchLink
import com.example.jellywrist.mobile.datalayer.WatchStatusRepository
import com.example.jellywrist.mobile.ui.OpState
import com.example.jellywrist.wire.WireAck
import com.example.jellywrist.wire.WireErrors
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.toWireBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pin/unpin feedback: the command used to be fire-and-forget — a failure produced no UI
 * change at all and the chip had no in-flight state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        link: FakeWatchLink,
        tokenStore: InMemoryTokenStore = InMemoryTokenStore(),
    ): BrowseViewModel {
        val backend = TestBackend()
        val authRepository = AuthRepository(backend, tokenStore)
        return BrowseViewModel(
            catalogRepository = PhoneCatalogRepository(authRepository, backend),
            authRepository = authRepository,
            watchStatusRepository = WatchStatusRepository(link),
        )
    }

    private val item = CatalogItem(
        id = "album-1",
        name = "Album",
        type = CatalogType.ALBUM,
        trackCount = 12,
        imageTag = null,
    )

    @Test
    fun `a successful pin moves through InFlight to Success and clears the pending id`() =
        runTest(mainDispatcher) {
            val link = FakeWatchLink()
            link.onSend = { path ->
                if (path == WirePaths.PIN) {
                    link.reply(WirePaths.PIN_REPLY, WireAck(ok = true).toWireBytes())
                }
            }
            val viewModel = viewModel(link)

            viewModel.togglePin(item, pin = true)
            assertTrue(item.id in viewModel.pendingIds.value)
            assertEquals(OpState.InFlight, viewModel.pinOp.value)

            advanceUntilIdle()

            assertEquals(OpState.Success, viewModel.pinOp.value)
            assertTrue(viewModel.pendingIds.value.isEmpty())
        }

    @Test
    fun `an unreachable watch surfaces an explicit error and clears the pending id`() =
        runTest(mainDispatcher) {
            val viewModel = viewModel(FakeWatchLink(node = null))

            viewModel.togglePin(item, pin = true)
            advanceUntilIdle()

            assertEquals(OpState.Error(WireErrors.WATCH_UNREACHABLE), viewModel.pinOp.value)
            assertTrue(viewModel.pendingIds.value.isEmpty())
        }

    @Test
    fun `a loaded tab is served from cache until the TTL expires or a refresh forces it`() =
        runTest(mainDispatcher) {
            val server = MockWebServer()
            server.start()
            try {
                repeat(3) {
                    server.enqueue(
                        MockResponse().setBody(
                            """{"Items":[{"Id":"00000000-0000-0000-0000-000000000001","Type":"Playlist","Name":"P","ChildCount":1}],"TotalRecordCount":1,"StartIndex":0}""",
                        ).addHeader("Content-Type", "application/json"),
                    )
                }
                val tokenStore =
                    InMemoryTokenStore(testSession(server.url("/").toString().trimEnd('/')))
                val viewModel = viewModel(FakeWatchLink(), tokenStore)
                var now = 0L
                viewModel.clock = { now }

                suspend fun awaitContent() {
                    advanceUntilIdle()
                    viewModel.tabs.first { it.getValue(CatalogType.PLAYLIST) is CatalogLoadState.Content }
                }

                viewModel.load(CatalogType.PLAYLIST)
                awaitContent()
                assertEquals(1, server.requestCount)

                // Within the TTL: tab switches must not re-paginate the catalog —
                // the load short-circuits synchronously, no Loading transition.
                viewModel.load(CatalogType.PLAYLIST)
                assertTrue(viewModel.tabs.value.getValue(CatalogType.PLAYLIST) is CatalogLoadState.Content)
                advanceUntilIdle()
                assertEquals(1, server.requestCount)

                // Explicit refresh bypasses the cache.
                viewModel.load(CatalogType.PLAYLIST, forceRefresh = true)
                awaitContent()
                assertEquals(2, server.requestCount)

                // TTL expired: the next load fetches again.
                now += 6 * 60 * 1_000L
                viewModel.load(CatalogType.PLAYLIST)
                awaitContent()
                assertEquals(3, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `a second toggle while the first is in flight is ignored`() = runTest(mainDispatcher) {
        val link = FakeWatchLink()
        var pins = 0
        link.onSend = { path ->
            if (path == WirePaths.PIN) {
                pins++
                link.reply(WirePaths.PIN_REPLY, WireAck(ok = true).toWireBytes())
            }
        }
        val viewModel = viewModel(link)

        viewModel.togglePin(item, pin = true)
        viewModel.togglePin(item, pin = true)
        advanceUntilIdle()

        assertEquals(1, pins)
    }
}
