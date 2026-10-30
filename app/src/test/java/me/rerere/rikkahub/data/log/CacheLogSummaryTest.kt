package me.rerere.rikkahub.data.log

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class CacheLogSummaryTest {
    private fun usage(session: String? = "opaque", provider: String = "provider", model: String = "model") = ChatLogEntry.RequestUsage(
        timestamp = 1000, modelId = model, providerId = provider, providerName = provider,
        promptTokens = 100, completionTokens = 20, cacheReadTokens = 60, cacheWriteTokens = 10,
        totalTokens = 120, sessionKey = session, usageReported = true, cacheUsageReported = true,
    )

    @Test
    fun `summary groups exact conversation channel and model and excludes unreported samples`() {
        val entries = listOf(
            usage(), usage().copy(cacheReadTokens = 0),
            usage().copy(cacheUsageReported = false, cacheReadTokens = 0, promptTokens = 10000),
            usage().copy(usageReported = false, cacheUsageReported = null, promptTokens = 0),
            usage().copy(usageReported = null, cacheUsageReported = null),
            usage(session = "other"), usage(provider = "other"), usage(model = "other"),
        )
        val summaries = summarizeCacheLogs(entries)
        assertEquals(4, summaries.size)
        val first = summaries.first()
        assertEquals(5, first.steps)
        assertEquals(3, first.usageReports)
        assertEquals(2, first.cacheReports)
        assertEquals(1, first.hitSteps)
        assertEquals(200L, first.inputTokens)
        assertEquals(60L, first.cacheReadTokens)
        assertEquals(20L, first.cacheWriteTokens)
        assertEquals(0.3, first.cacheHitRate!!, 0.00001)
    }

    @Test
    fun `missing usage is not a measured zero hit and old logs remain readable`() {
        assertNull(usage().copy(usageReported = false).cacheHitRate)
        assertNull(usage().copy(cacheUsageReported = false).cacheHitRate)
        assertEquals(0.0, usage().copy(cacheReadTokens = 0).cacheHitRate!!, 0.00001)
        val oldJson = """{"type":"request_usage","timestamp":1,"modelId":"m","providerId":"p","providerName":"p","promptTokens":100,"completionTokens":0,"cacheReadTokens":0,"cacheWriteTokens":0,"totalTokens":100}"""
        val old = JsonInstant.decodeFromString<ChatLogEntry>(oldJson) as ChatLogEntry.RequestUsage
        assertNull(old.usageReported)
        assertNull(old.cacheUsageReported)
        assertEquals(0, summarizeCacheLogs(listOf(old)).single().cacheReports)
        assertNull(summarizeCacheLogs(listOf(old)).single().cacheHitRate)
        val entry = usage()
        assertEquals(entry, JsonInstant.decodeFromString<ChatLogEntry>(JsonInstant.encodeToString<ChatLogEntry>(entry)))
    }

    @Test
    fun `summary uses long counters`() {
        val big = usage().copy(promptTokens = Int.MAX_VALUE, cacheReadTokens = Int.MAX_VALUE)
        val summary = summarizeCacheLogs(listOf(big, big)).single()
        assertEquals(2L * Int.MAX_VALUE, summary.inputTokens)
        assertEquals(1.0, summary.cacheHitRate!!, 0.00001)
    }
}
