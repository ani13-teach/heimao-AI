package me.rerere.rikkahub.data.log

import io.modelcontextprotocol.kotlin.sdk.types.McpException
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogEntryTest {
    private fun usage(
        prompt: Int = 0,
        completion: Int = 0,
        cacheRead: Int = 0,
        cacheWrite: Int = 0,
    ) = ChatLogEntry.RequestUsage(
        timestamp = 1_000L,
        modelId = "gpt-4o",
        providerId = "provider-1",
        providerName = "OpenAI",
        promptTokens = prompt,
        completionTokens = completion,
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite,
        totalTokens = prompt + completion,
    )

    @Test
    fun `cache hit rate is cache read over prompt tokens`() {
        assertEquals(0.5, usage(prompt = 200, cacheRead = 100).cacheHitRate!!, 0.0001)
    }

    @Test
    fun `cache hit rate is null when nothing was sent`() {
        assertNull(usage().cacheHitRate)
    }

    @Test
    fun `cache hit rate ignores cache writes`() {
        // 写多读少时不能把命中率算歪：分母只看输入 token。
        assertEquals(0.0, usage(prompt = 100, cacheWrite = 900).cacheHitRate!!, 0.0001)
    }

    @Test
    fun `errors only level keeps failures and crashes but drops usage`() {
        val failure = ChatLogEntry.ToolFailure(
            timestamp = 1_000L,
            toolName = "workspace_shell",
            errorType = "IllegalStateException",
            errorMessage = "boom",
        )
        val crash = ChatLogEntry.Crash(
            timestamp = 1_000L,
            threadName = "main",
            errorType = "NullPointerException",
            stackTrace = "stack",
        )

        assertFalse(ChatLogLevel.OFF.allows(usage(prompt = 1)))
        assertFalse(ChatLogLevel.OFF.allows(failure))
        assertFalse(ChatLogLevel.ERRORS_ONLY.allows(usage(prompt = 1)))
        assertTrue(ChatLogLevel.ERRORS_ONLY.allows(failure))
        assertTrue(ChatLogLevel.ERRORS_ONLY.allows(crash))
        assertTrue(ChatLogLevel.ALL.allows(usage(prompt = 1)))
        assertTrue(ChatLogLevel.ALL.allows(failure))
    }

    @Test
    fun `entries survive a json round trip`() {
        val entries: List<ChatLogEntry> = listOf(
            usage(prompt = 120, completion = 30, cacheRead = 60, cacheWrite = 10),
            ChatLogEntry.ToolFailure(
                timestamp = 2_000L,
                toolName = "mcp__server__tool",
                errorType = "McpException",
                errorMessage = "tool exploded",
                upstreamCode = "-32602",
            ),
            ChatLogEntry.Crash(
                timestamp = 3_000L,
                threadName = "main",
                errorType = "IllegalStateException",
                stackTrace = "at foo(Bar.kt:1)",
            ),
        )

        val decoded = entries.map {
            JsonInstant.decodeFromString<ChatLogEntry>(JsonInstant.encodeToString<ChatLogEntry>(it))
        }

        assertEquals(entries, decoded)
    }

    @Test
    fun `corrupt line does not decode into an entry`() {
        assertNull(runCatching { JsonInstant.decodeFromString<ChatLogEntry>("{not json") }.getOrNull())
    }

    @Test
    fun `secrets in a failure reason are redacted`() {
        val redacted = sanitizeLogText(
            "401 unauthorized: Bearer abcdef1234567890 and api_key=supersecretvalue"
        )

        assertFalse(redacted.contains("abcdef1234567890"))
        assertFalse(redacted.contains("supersecretvalue"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `long text is truncated after redaction`() {
        assertEquals(20, sanitizeLogText("y".repeat(100), maxChars = 20).length)

        // 先脱敏再截断：否则 secret 会被截成半截，模式就认不出来了。
        val redacted = sanitizeLogText("api_key=" + "k".repeat(40), maxChars = 20)
        assertEquals("[REDACTED]", redacted)
    }

    @Test
    fun `mcp exception exposes its upstream code`() {
        assertEquals("-32602", extractUpstreamCode(McpException(-32602, "invalid params")))
    }

    @Test
    fun `http status is picked out of a plain failure`() {
        assertEquals("404", extractUpstreamCode(RuntimeException("HTTP 404 Not Found")))
    }

    @Test
    fun `failure without a code reports null`() {
        assertNull(extractUpstreamCode(RuntimeException("something broke")))
    }
}
