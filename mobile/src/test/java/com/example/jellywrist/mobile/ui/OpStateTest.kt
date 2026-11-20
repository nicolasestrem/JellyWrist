package com.example.jellywrist.mobile.ui

import com.example.jellywrist.wire.WireAck
import com.example.jellywrist.wire.WireErrors
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Result-to-[OpState] folding for the remote watch commands. */
class OpStateTest {

    @Test
    fun `an ok ack folds to Success`() {
        assertEquals(OpState.Success, Result.success(WireAck(ok = true)).toOpState())
    }

    @Test
    fun `a refused ack surfaces the watch error code`() {
        assertEquals(
            OpState.Error(WireErrors.NO_SESSION),
            Result.success(WireAck(ok = false, error = WireErrors.NO_SESSION)).toOpState(),
        )
    }

    @Test
    fun `a refused ack without code falls back to INTERNAL`() {
        assertEquals(
            OpState.Error(WireErrors.INTERNAL),
            Result.success(WireAck(ok = false)).toOpState(),
        )
    }

    @Test
    fun `an unreachable watch maps to WATCH_UNREACHABLE`() {
        val failure = Result.failure<WireAck>(IllegalStateException(WireErrors.WATCH_UNREACHABLE))
        assertEquals(OpState.Error(WireErrors.WATCH_UNREACHABLE), failure.toOpState())
    }

    @Test
    fun `an expired wait maps to TIMEOUT`() = runTest {
        val timeout = runCatching {
            withTimeout(1) { kotlinx.coroutines.suspendCancellableCoroutine<Unit> {} }
        }.exceptionOrNull()!!
        assertEquals(OpState.Error(WireErrors.TIMEOUT), Result.failure<WireAck>(timeout).toOpState())
    }

    @Test
    fun `anything else maps to INTERNAL`() {
        val failure = Result.failure<WireAck>(RuntimeException("boom"))
        assertEquals(OpState.Error(WireErrors.INTERNAL), failure.toOpState())
    }
}
