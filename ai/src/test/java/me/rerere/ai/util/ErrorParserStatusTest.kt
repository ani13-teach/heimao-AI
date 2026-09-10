package me.rerere.ai.util

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.isRetryableFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorParserStatusTest {
    @Test
    fun `keeps the status code through a nested error field`() {
        val error = Json.parseToJsonElement("""{"error":{"message":"invalid api key"}}""")
            .parseErrorDetail(status = 401)

        assertEquals("invalid api key", error.message)
        assertEquals(401, error.status)
    }

    @Test
    fun `leaves the status unknown when the caller has no response`() {
        val error = Json.parseToJsonElement("""{"error":"overloaded"}""").parseErrorDetail()

        assertEquals("overloaded", error.message)
        assertNull(error.status)
    }

    @Test
    fun `keeps the status for a primitive payload`() {
        val error = Json.parseToJsonElement("\"rate limited\"").parseErrorDetail(status = 429)

        assertEquals("rate limited", error.message)
        assertEquals(429, error.status)
    }

    @Test
    fun `keeps the status when the payload has no known error field`() {
        val error = Json.parseToJsonElement("""{"unexpected":true}""").parseErrorDetail(status = 502)

        assertEquals("""{"unexpected":true}""", error.message)
        assertEquals(502, error.status)
    }

    @Test
    fun `keeps the status for an empty array payload`() {
        val error = Json.parseToJsonElement("""[]""").parseErrorDetail(status = 500)

        assertEquals("Unknown error: Empty JSON array", error.message)
        assertEquals(500, error.status)
    }

    @Test
    fun `parsed server errors are retryable and parsed client errors are not`() {
        val serverError = Json.parseToJsonElement("""{"error":{"message":"bad gateway"}}""")
            .parseErrorDetail(status = 502)
        val clientError = Json.parseToJsonElement("""{"error":{"message":"bad request"}}""")
            .parseErrorDetail(status = 400)

        assertTrue(isRetryableFailure(serverError))
        assertFalse(isRetryableFailure(clientError))
    }
}
