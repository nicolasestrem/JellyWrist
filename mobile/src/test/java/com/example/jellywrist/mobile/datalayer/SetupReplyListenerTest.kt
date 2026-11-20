package com.example.jellywrist.mobile.datalayer

import com.example.jellywrist.wire.SetupQcCode
import com.example.jellywrist.wire.SetupResult
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.toWireBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the subscription races of provisioning: `WatchLink.incoming()` does not replay,
 * a result emitted while no `await` is armed would be permanently lost.
 */
class SetupReplyListenerTest {

    @Test
    fun `terminal result sent during authorization is not lost`() = runTest {
        val link = FakeWatchLink()
        val listener = SetupReplyListener(link, FakeWatchLink.NODE, this)

        link.reply(WirePaths.SETUP_QC, SetupQcCode("123456").toWireBytes())
        val first = listener.awaitFirstReply(30.seconds)

        // The watch polls approval continuously: it can conclude while the phone's
        // authorization call is still in flight, hence before any `awaitResult`.
        link.reply(
            WirePaths.SETUP_RESULT,
            SetupResult(ok = true, userName = "Avery").toWireBytes(),
        )

        val result = listener.awaitResult(90.seconds)
        listener.cancel()

        assertEquals(InitialSetupReply.QcCode("123456"), first)
        assertEquals(SetupResult(ok = true, userName = "Avery"), result)
    }

    @Test
    fun `immediate terminal result ends the first phase`() = runTest {
        val link = FakeWatchLink()
        val listener = SetupReplyListener(link, FakeWatchLink.NODE, this)

        // Watch already configured: it replies SETUP_RESULT without ever emitting a code.
        link.reply(
            WirePaths.SETUP_RESULT,
            SetupResult(ok = true, alreadyConfigured = true, userName = "Avery").toWireBytes(),
        )
        val first = listener.awaitFirstReply(30.seconds)
        listener.cancel()

        assertTrue(first is InitialSetupReply.Result)
        assertTrue((first as InitialSetupReply.Result).result.alreadyConfigured)
    }

    @Test
    fun `messages from another node are ignored`() = runTest {
        val link = FakeWatchLink()
        val listener = SetupReplyListener(link, "other-node", this)

        link.reply(WirePaths.SETUP_QC, SetupQcCode("123456").toWireBytes())
        val first = listener.awaitFirstReply(30.seconds)
        listener.cancel()

        assertNull(first)
    }

    @Test
    fun `a transport failure expires instead of cancelling the caller`() = runTest {
        // The listener is a child of the provisioning coroutine: if it propagated
        // the error, the entire exchange would be cancelled without the UI showing anything.
        val broken = object : WatchLink {
            override suspend fun discoverWatch() =
                WatchDiscovery.Found(FakeWatchLink.NODE, FakeWatchLink.WATCH_NAME)
            override suspend fun send(nodeId: String, path: String, payload: ByteArray) = Unit
            override suspend fun ensureListening() = Unit
            override fun incoming(): Flow<WatchMessage> = flow { error("data layer down") }
        }
        val listener = SetupReplyListener(broken, FakeWatchLink.NODE, this)

        val first = listener.awaitFirstReply(30.seconds)
        listener.cancel()

        assertNull(first)
    }

    @Test
    fun `malformed payload does not resolve the exchange`() = runTest {
        val link = FakeWatchLink()
        val listener = SetupReplyListener(link, FakeWatchLink.NODE, this)

        link.reply(WirePaths.SETUP_RESULT, "not JSON".toByteArray())
        val result = listener.awaitResult(90.seconds)
        listener.cancel()

        assertNull(result)
    }
}
