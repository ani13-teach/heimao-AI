package me.rerere.ai.provider

import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageNormalizer
import me.rerere.ai.core.merge
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.claude.ClaudeStreamDecoder
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.providers.openai.*
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class CacheUsageReportingTest {
    private fun parse(target: Any, methodName: String, json: JsonObject): TokenUsage? {
        val method = target.javaClass.getDeclaredMethod(methodName, JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(target, json) as TokenUsage?
    }

    @Test
    fun `all adapters distinguish absent cache counters from explicit zero`() {
        val client = OkHttpClient()
        val cases = listOf(
            Triple(ChatCompletionsAPI(client, KeyRoulette.default()), "parseTokenUsage", "chat"),
            Triple(ChatCompletionsStreamDecoder(), "parseUsage", "chat"),
            Triple(ResponseAPI(client), "parseTokenUsage", "response"),
            Triple(ResponseApiStreamDecoder(), "parseUsage", "response"),
            Triple(ClaudeProvider(client), "parseTokenUsage", "claude"),
            Triple(ClaudeStreamDecoder(), "parseTokenUsage", "claude"),
            Triple(GoogleProvider(client), "parseUsageMeta", "google"),
            Triple(GoogleStreamDecoder("response", "gemini-test"), "parseUsage", "google"),
        )
        for ((target, method, format) in cases) {
            fun body(reported: Boolean) = buildJsonObject {
                when (format) {
                    "chat" -> {
                        put("prompt_tokens", 100); put("completion_tokens", 20)
                        if (reported) put("prompt_tokens_details", buildJsonObject { put("cached_tokens", 0) })
                    }
                    "response" -> {
                        put("input_tokens", 100); put("output_tokens", 20)
                        if (reported) put("input_tokens_details", buildJsonObject { put("cached_tokens", 0) })
                    }
                    "claude" -> put("usage", buildJsonObject {
                        put("input_tokens", 100); put("output_tokens", 20)
                        if (reported) { put("cache_read_input_tokens", 0); put("cache_creation_input_tokens", 0) }
                    })
                    "google" -> {
                        put("promptTokenCount", 100); put("candidatesTokenCount", 20)
                        if (reported) put("cachedContentTokenCount", 0)
                    }
                }
            }
            val missing = parse(target, method, body(false))!!
            val zero = parse(target, method, body(true))!!
            assertEquals(target.javaClass.simpleName, false, missing.cacheUsageReported)
            assertEquals(target.javaClass.simpleName, true, zero.cacheUsageReported)
            assertEquals(100, missing.promptTokens)
            assertEquals(0, missing.cacheReadTokens)
            assertEquals(0, zero.cacheReadTokens)
        }
    }

    @Test
    fun `stream usage merge retains earlier cache evidence when terminal chunk omits it`() {
        val read = UsageNormalizer.normalize(totalInputTokens = 100, cacheReadTokens = 60)
        val terminal = UsageNormalizer.normalize(totalInputTokens = null, completionTokens = 20)
        val merged = read.merge(terminal)
        assertEquals(true, merged.cacheUsageReported)
        assertEquals(60, merged.cacheReadTokens)
        assertEquals(100, merged.promptTokens)
        assertEquals(20, merged.completionTokens)
        assertEquals(false, terminal.merge(terminal).cacheUsageReported)
        val legacy = Json.decodeFromString<TokenUsage>("""{"promptTokens":100}""")
        assertNull(legacy.cacheUsageReported)
    }
}
