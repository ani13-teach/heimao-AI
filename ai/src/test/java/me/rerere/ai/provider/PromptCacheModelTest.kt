package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.UsageNormalizer
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheModelTest {
    @Test
    fun `logical prompt keeps provider independent section order`() {
        val prompt = LogicalPrompt(
            system = listOf(UIMessage.system("system")),
            history = listOf(UIMessage.user("history")),
            transientContext = listOf(UIMessage.user("retrieval")),
            memorySnapshot = listOf(UIMessage.system("memory")),
            claudeMemoryCacheBlocks = listOf(
                ClaudeMemoryCacheBlock("claude base"),
                ClaudeMemoryCacheBlock("claude patch"),
            ),
            claudeMemoryDelta = listOf(UIMessage.user("claude dynamic patch")),
            latestUser = UIMessage.user("latest"),
            currentToolLoop = listOf(UIMessage.assistant("tool loop")),
        )

        assertEquals(
            listOf("system", "memory", "history", "retrieval", "latest", "tool loop"),
            prompt.toMessages().map { it.toText() },
        )
        assertEquals(
            listOf("system", "memory", "history", "retrieval", "latest", "tool loop"),
            prompt.toProviderMessages(includeSystem = true).map { it.toText() },
        )
        assertEquals(MessageRole.USER, prompt.toProviderMessages(false)[2].role)
        assertTrue(prompt.toMessages().none { it.toText().contains("claude") })
    }

    @Test
    fun `session cache key is stable opaque and session scoped`() {
        val first = PromptCacheKey.forSession("conversation-1")
        val repeat = PromptCacheKey.forSession("conversation-1")
        val other = PromptCacheKey.forSession("conversation-2")
        val otherApp = PromptCacheKey.forSession("conversation-1", appId = "other-app")

        assertEquals(first, repeat)
        assertNotEquals(first, other)
        assertNotEquals(first, otherApp)
        assertTrue(first.startsWith("chat_"))
        assertTrue(first.removePrefix("chat_").matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `usage normalizer keeps read write and compatibility alias`() {
        val usage = UsageNormalizer.normalize(
            totalInputTokens = 100,
            completionTokens = 20,
            totalTokens = 120,
            cacheReadTokens = 60,
            cacheWriteTokens = 15,
            provider = "claude",
            model = "claude-test",
        )
        assertEquals(100, usage.promptTokens)
        assertEquals(60, usage.cachedTokens)
        assertEquals(60, usage.cacheReadTokens)
        assertEquals(15, usage.cacheWriteTokens)
        assertEquals(120, usage.totalTokens)
        assertEquals("claude", usage.provider)
        assertEquals("claude-test", usage.model)

        val cacheUsage = UsageNormalizer.toCacheUsage(usage, "claude", "claude-test")
        assertEquals(100, cacheUsage.totalInputTokens)
        assertEquals(60, cacheUsage.cacheReadTokens)
        assertEquals(15, cacheUsage.cacheWriteTokens)
        assertEquals("claude", cacheUsage.provider)
        assertEquals("claude-test", cacheUsage.model)
        assertNull(UsageNormalizer.toCacheUsage(null, "openai", "gpt-test").totalInputTokens)
    }

    @Test
    fun `old serialized token usage remains readable`() {
        val usage = Json.decodeFromString<me.rerere.ai.core.TokenUsage>(
            "{\"promptTokens\":10,\"completionTokens\":2,\"cachedTokens\":1,\"totalTokens\":12}"
        )
        assertEquals(1, usage.cacheReadTokens)
        assertEquals(0, usage.cacheWriteTokens)
    }
}
