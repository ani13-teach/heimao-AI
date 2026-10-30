package me.rerere.ai.provider

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class PromptCacheDiagnosticsTest {
    private val wire = buildJsonObject { put("tools", JsonArray(emptyList())) }
    private val prompt = LogicalPrompt(
        system = listOf(UIMessage.system("private system")),
        memorySnapshot = listOf(UIMessage.system("private memory")),
        history = listOf(UIMessage.user("private history")),
        latestUser = UIMessage.user("latest"),
    )
    private fun snapshot(prompt: LogicalPrompt = this.prompt, body: JsonObject = wire, custom: List<CustomBody> = emptyList()): PromptCacheSnapshot {
        var snapshot: PromptCacheSnapshot? = null
        assertSame(body, body.observePromptCacheRequest(
            TextGenerationParams(Model(), logicalPrompt = prompt, customBody = custom, onPromptCacheSnapshot = { snapshot = it }),
            PromptCacheApi.CHAT_COMPLETIONS,
        ))
        return snapshot!!
    }

    @Test
    fun `stable content ignores message identity and metadata while append and rewrite differ`() {
        val tracker = PromptCacheTracker()
        val baseline = snapshot()
        assertTrue(tracker.observe("session", baseline).baseline)
        val clone = prompt.copy(history = listOf(UIMessage.user("private history")))
        assertTrue(tracker.observe("session", snapshot(clone)).changes.isEmpty())
        val append = clone.copy(history = clone.history + UIMessage.assistant("answer"))
        assertEquals(mapOf(PromptCacheSection.HISTORY to PromptCacheChange.APPENDED), tracker.observe("session", snapshot(append)).changes)
        val rewrite = append.copy(system = listOf(UIMessage.system("new system")))
        assertEquals(PromptCacheChange.CHANGED, tracker.observe("session", snapshot(rewrite)).changes[PromptCacheSection.SYSTEM])
        assertFalse(baseline.toString().contains("private"))
        val stored = Json.encodeToString(PromptCacheDiagnostic.serializer(), tracker.observe("session", snapshot(rewrite)))
        assertFalse(stored.contains("private"))
        baseline.sections.values.flatten().forEach { assertFalse(stored.contains(it)) }
    }

    @Test
    fun `sessions stay isolated tracker is bounded and clearing resets baseline`() {
        val tracker = PromptCacheTracker(1)
        assertTrue(tracker.observe("one", snapshot()).baseline)
        assertTrue(tracker.observe("two", snapshot()).baseline)
        assertTrue(tracker.observe("one", snapshot()).baseline)
        tracker.clear()
        assertTrue(tracker.observe("one", snapshot()).baseline)
    }

    @Test
    fun `Claude base is stable while pending patches are dynamic and compaction rewrites memory`() {
        fun claude(p: LogicalPrompt): PromptCacheSnapshot {
            var value: PromptCacheSnapshot? = null
            wire.observePromptCacheRequest(
                TextGenerationParams(Model(), logicalPrompt = p, onPromptCacheSnapshot = { value = it }),
                PromptCacheApi.CLAUDE, listOf(CacheBoundary.SYSTEM_END, CacheBoundary.MEMORY_END),
            )
            return value!!
        }
        val tracker = PromptCacheTracker()
        val base = prompt.copy(claudeMemoryCacheBlocks = listOf(ClaudeMemoryCacheBlock("base")))
        tracker.observe("s", claude(base))
        val patch = base.copy(memorySnapshot = listOf(UIMessage.system("new current memory")), claudeMemoryDelta = listOf(UIMessage.user("patch")))
        val result = tracker.observe("s", claude(patch))
        assertFalse(result.changes.containsKey(PromptCacheSection.MEMORY))
        assertEquals(PromptCacheChange.APPENDED, result.changes[PromptCacheSection.MEMORY_DELTA])
        assertEquals(2, result.appliedBoundaries.size)
        val compact = patch.copy(claudeMemoryCacheBlocks = listOf(ClaudeMemoryCacheBlock("compacted")), claudeMemoryDelta = emptyList())
        assertEquals(PromptCacheChange.CHANGED, tracker.observe("s", claude(compact)).changes[PromptCacheSection.MEMORY])
    }

    @Test
    fun `custom bodies use actual wire fingerprints and disabled observers are no ops`() {
        val custom = listOf(CustomBody("messages", JsonArray(emptyList())))
        val first = snapshot(custom = custom)
        assertEquals(setOf(PromptCacheSection.CUSTOM_PROMPT), first.sections.keys)
        val body = buildJsonObject { put("messages", JsonArray(listOf(JsonPrimitive("changed")))); put("prompt_cache_key", "opaque") }
        val second = snapshot(body = body, custom = custom)
        assertNotEquals(first.sections, second.sections)
        assertTrue(second.cacheKeySent)
        assertSame(wire, wire.observePromptCacheRequest(TextGenerationParams(Model()), PromptCacheApi.GEMINI))
        assertSame(wire, wire.observePromptCacheRequest(TextGenerationParams(Model(), onPromptCacheSnapshot = { error("log failure") }), PromptCacheApi.GEMINI))
    }
}
