package me.rerere.ai.provider

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun `retries after a transient failure with the configured interval`() {
        val policy = RetryPolicy(intervalMillis = 3_000, maxDurationMillis = 60_000)

        val delay = policy.delayBeforeRetry(
            failedAttempt = 1,
            elapsedMillis = 0,
            error = IOException("connection reset"),
        )

        assertEquals(3_000L, delay)
    }

    @Test
    fun `stops when the next wait would exceed the budget`() {
        val policy = RetryPolicy(intervalMillis = 5_000, maxDurationMillis = 10_000)

        val delay = policy.delayBeforeRetry(
            failedAttempt = 2,
            elapsedMillis = 8_000,
            error = IOException("timeout"),
        )

        assertNull(delay)
    }

    @Test
    fun `retries when the wait lands exactly on the budget boundary`() {
        val policy = RetryPolicy(intervalMillis = 5_000, maxDurationMillis = 10_000)

        val delay = policy.delayBeforeRetry(
            failedAttempt = 2,
            elapsedMillis = 5_000,
            error = IOException("timeout"),
        )

        assertEquals(5_000L, delay)
    }

    @Test
    fun `stops at the fixed attempt limit`() {
        val policy = RetryPolicy(
            intervalMillis = 1_000,
            maxDurationMillis = 10 * 60_000,
            maxAttempts = 3,
        )

        assertEquals(
            1_000L,
            policy.delayBeforeRetry(1, elapsedMillis = 0, error = IOException("boom")),
        )
        assertEquals(
            1_000L,
            policy.delayBeforeRetry(2, elapsedMillis = 0, error = IOException("boom")),
        )
        assertNull(policy.delayBeforeRetry(3, elapsedMillis = 0, error = IOException("boom")))
    }

    @Test
    fun `keeps a positive interval when configured below one millisecond`() {
        val policy = RetryPolicy(intervalMillis = 0, maxDurationMillis = 60_000)

        val delay = policy.delayBeforeRetry(
            failedAttempt = 1,
            elapsedMillis = 0,
            error = IOException("boom"),
        )

        assertEquals(1L, delay)
    }

    @Test
    fun `treats connection and timeout failures as retryable`() {
        assertTrue(isRetryableFailure(IOException("unexpected end of stream")))
        assertTrue(isRetryableFailure(SocketTimeoutException("read timed out")))
    }

    @Test
    fun `treats client errors as not retryable`() {
        assertFalse(isRetryableFailure(HttpException("invalid api key", status = 401)))
        assertFalse(isRetryableFailure(HttpException("forbidden", status = 403)))
        assertFalse(isRetryableFailure(HttpException("bad request", status = 400)))
    }

    @Test
    fun `treats rate limits and server errors as retryable`() {
        assertTrue(isRetryableFailure(HttpException("rate limited", status = 429)))
        assertTrue(isRetryableFailure(HttpException("server error", status = 500)))
        assertTrue(isRetryableFailure(HttpException("unavailable", status = 503)))
    }

    @Test
    fun `treats an error without a status code as retryable`() {
        assertTrue(isRetryableFailure(HttpException("overloaded")))
    }

    @Test
    fun `never retries cancellation`() {
        assertFalse(isRetryableFailure(CancellationException("cancelled")))
    }

    @Test
    fun `never retries cancellation even when the budget allows it`() {
        val policy = RetryPolicy(intervalMillis = 1_000, maxDurationMillis = 60_000)

        assertNull(
            policy.delayBeforeRetry(
                failedAttempt = 1,
                elapsedMillis = 0,
                error = CancellationException("cancelled"),
            )
        )
    }
}
