package com.example.jellywrist.mobile.data

import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.mobile.InMemoryTokenStore
import com.example.jellywrist.mobile.TestBackend
import com.example.jellywrist.mobile.testSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Verifies the catalog contract sent to the watch pin commands. */
class PhoneCatalogRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `collections maps playlist DTO fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"Items":[{"Id":"00000000-0000-0000-0000-000000000001","Type":"Playlist","Name":"Ma playlist","ChildCount":12,"ImageTags":{"Primary":"cover-v1"}}],"TotalRecordCount":1,"StartIndex":0}""",
            ).addHeader("Content-Type", "application/json"),
        )
        val tokenStore = InMemoryTokenStore(testSession(server.url("/").toString().trimEnd('/')))
        val repository = PhoneCatalogRepository(
            AuthRepository(TestBackend(), tokenStore),
            TestBackend(),
        )

        val result = repository.collections(CatalogType.PLAYLIST).getOrThrow()

        assertEquals(
            listOf(CatalogItem("00000000-0000-0000-0000-000000000001", "Ma playlist", CatalogType.PLAYLIST, 12, "cover-v1")),
            result,
        )
        assertEquals("/Items", server.takeRequest().path?.substringBefore('?'))
    }

    @Test
    fun `catalog type names match the watch wire contract`() {
        assertEquals("PLAYLIST", CatalogType.PLAYLIST.name)
        assertEquals("ALBUM", CatalogType.ALBUM.name)
        assertEquals("ARTIST", CatalogType.ARTIST.name)
    }
}
