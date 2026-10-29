package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.contextSourceHash
import me.rerere.rikkahub.data.ai.estimateTextTokens
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ContextToolsTest {
    private fun fixture(text: String = "alpha needle omega"): Pair<Conversation, ContextBlock> {
        val c = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            MessageNode.of(UIMessage.user(text)), MessageNode.of(UIMessage.assistant("answer")),
            MessageNode.of(UIMessage.user("previous")), MessageNode.of(UIMessage.assistant("done")),
            MessageNode.of(UIMessage.user("current")),
        ))
        val b = ContextBlock(conversationId = c.id, assistantId = c.assistantId,
            sources = c.messageNodes.take(2).map { ContextBlockSource(it.id, it.currentMessage.id) },
            sourceHash = contextSourceHash(c.currentMessages.take(2)), prefixHash = contextSourceHash(c.currentMessages.take(2)),
            summary = "summary does not contain the keyword")
        return c to b
    }
    private suspend fun Tool.call(input: JsonObject): JsonObject {
        val text = (execute(input).single() as UIMessagePart.Text).text
        assertTrue(estimateTextTokens(text) <= 2048)
        return Json.parseToJsonElement(text).jsonObject
    }
    @Test fun `summary misses can search original and read tool text without reasoning or binaries`() = runBlocking {
        val (base, originalBlock) = fixture()
        val message = base.currentMessages[1].copy(parts = listOf(UIMessagePart.Text("answer"),
            UIMessagePart.Reasoning("SECRET_REASONING"),
            UIMessagePart.Tool("t", "shell", "pwd", listOf(UIMessagePart.Text("needle tool output"))),
            UIMessagePart.Image("data:image/png;base64,SECRET_BINARY")))
        val c = base.updateCurrentMessages(listOf(message))
        val b = originalBlock.copy(sourceHash = contextSourceHash(c.currentMessages.take(2)), prefixHash = contextSourceHash(c.currentMessages.take(2)))
        val tools = createContextTools(c.assistantId, c.id, { c }, { listOf(b) }).associateBy { it.name }
        val miss = tools.getValue("context_search").call(buildJsonObject { put("query", "needle") })
        assertEquals(0, miss.getValue("items").jsonArray.size)
        val hit = tools.getValue("context_search").call(buildJsonObject { put("query", "needle"); put("scope", "original") })
        assertEquals(2, hit.getValue("items").jsonArray.size)
        val read = tools.getValue("context_read").call(buildJsonObject { put("block_id", b.id.toString()); put("view", "original") })
        assertTrue(read.toString().contains("needle tool output"))
        assertFalse(read.toString().contains("SECRET_REASONING"))
        assertFalse(read.toString().contains("SECRET_BINARY"))
    }
    @Test fun `original pagination preserves unicode and enforces cumulative turn budget across tool instances`() = runBlocking {
        val (c, b) = fixture("中文😀\\\"\n".repeat(4000))
        val tool = createContextTools(c.assistantId, c.id, { c }, { listOf(b) }).last()
        val first = tool.call(buildJsonObject { put("block_id", b.id.toString()); put("view", "original") })
        assertTrue(first.getValue("truncated").jsonPrimitive.boolean)
        val text = first.getValue("content").jsonPrimitive.content
        assertFalse(text.last().isHighSurrogate())
        val next = first.getValue("next_cursor").jsonPrimitive.content
        val second = tool.call(buildJsonObject { put("block_id", b.id.toString()); put("view", "original"); put("cursor", next) })
        assertEquals("OK", second.getValue("status").jsonPrimitive.content)
        val third = tool.call(buildJsonObject { put("block_id", b.id.toString()); put("view", "original") })
        assertEquals("BUDGET_EXCEEDED", third.getValue("status").jsonPrimitive.content)
        val resumed = c.copy(messageNodes = c.messageNodes + MessageNode.of(UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("r1", "context_read", "{}", listOf(UIMessagePart.Text(first.toString()))),
            UIMessagePart.Tool("r2", "context_read", "{}", listOf(UIMessagePart.Text(second.toString()))),
        ))))
        val newTool = createContextTools(c.assistantId, c.id, { resumed }, { listOf(b) }).last()
        assertEquals("BUDGET_EXCEEDED", newTool.call(buildJsonObject { put("block_id", b.id.toString()) }).getValue("status").jsonPrimitive.content)
    }
    @Test fun `scope branch edits and forged cursors are rejected`() = runBlocking {
        val (c, b) = fixture("needle".repeat(3000))
        var current = c
        val tool = createContextTools(c.assistantId, c.id, { current }, { listOf(b) }).last()
        val first = tool.call(buildJsonObject { put("block_id", b.id.toString()); put("view", "original") })
        val next = first.getValue("next_cursor").jsonPrimitive.content
        val bad = tool.call(buildJsonObject { put("block_id", b.id.toString()); put("cursor", next) })
        assertEquals("STALE", bad.getValue("status").jsonPrimitive.content)
        current = c.updateCurrentMessages(listOf(c.currentMessages.first().copy(parts = listOf(UIMessagePart.Text("edited")))))
        assertEquals("NOT_FOUND", tool.call(buildJsonObject { put("block_id", b.id.toString()) }).getValue("status").jsonPrimitive.content)
        current = c.copy(assistantId = Uuid.random())
        assertEquals("NOT_FOUND", tool.call(buildJsonObject { put("block_id", b.id.toString()) }).getValue("status").jsonPrimitive.content)
    }
    @Test fun `search pages contain each matching source exactly once`() = runBlocking {
        val (c, b) = fixture("answer needle")
        val search = createContextTools(c.assistantId, c.id, { c }, { listOf(b) }).first()
        val first = search.call(buildJsonObject { put("query", "answer"); put("scope", "original"); put("limit", 1) })
        assertEquals(1, first.getValue("items").jsonArray.size)
        val next = first.getValue("next_cursor").jsonPrimitive.content
        val second = search.call(buildJsonObject { put("query", "answer"); put("scope", "original"); put("limit", 1); put("cursor", next) })
        assertEquals(1, second.getValue("items").jsonArray.size)
        assertNotEquals(first.getValue("items"), second.getValue("items"))
        assertFalse(second.getValue("truncated").jsonPrimitive.boolean)
    }
    @Test fun `search hides covered snapshots but old IDs and their cursors remain readable`() = runBlocking {
        val (c, old) = fixture("needle ".repeat(3000))
        val merged = old.copy(id = Uuid.random(), summary = "new needle facts", formatVersion = 2, createdAt = old.createdAt + 1)
        var blocks = listOf(old)
        fun tools() = createContextTools(c.assistantId, c.id, { c }, { blocks }).associateBy { it.name }
        val first = tools().getValue("context_read").call(buildJsonObject {
            put("block_id", old.id.toString()); put("view", "original")
        })
        val next = first.getValue("next_cursor").jsonPrimitive.content
        blocks = listOf(old, merged)
        val activeTools = tools()
        val search = activeTools.getValue("context_search").call(buildJsonObject { put("query", "needle") })
        assertEquals(listOf(merged.id.toString()), search.getValue("items").jsonArray.map {
            it.jsonObject.getValue("block_id").jsonPrimitive.content
        })
        val resumed = activeTools.getValue("context_read").call(buildJsonObject {
            put("block_id", old.id.toString()); put("view", "original"); put("cursor", next)
        })
        assertEquals("OK", resumed.getValue("status").jsonPrimitive.content)
        val oldSummary = tools().getValue("context_read").call(buildJsonObject { put("block_id", old.id.toString()) })
        assertEquals(old.summary, oldSummary.getValue("content").jsonPrimitive.content)
        val originalSearch = tools().getValue("context_search").call(buildJsonObject {
            put("query", "needle"); put("scope", "original")
        })
        assertEquals(1, originalSearch.getValue("items").jsonArray.size)
    }

    @Test fun `search and read cursors bind summary contents not only source hash`() = runBlocking {
        val (c, base) = fixture()
        var block = base.copy(summary = "needle ".repeat(3000), formatVersion = 2)
        fun tools() = createContextTools(c.assistantId, c.id, { c }, { listOf(block) }).associateBy { it.name }
        val read = tools().getValue("context_read").call(buildJsonObject { put("block_id", block.id.toString()) })
        val readCursor = read.getValue("next_cursor").jsonPrimitive.content
        block = block.copy(summary = "changed " + block.summary)
        val stale = tools().getValue("context_read").call(buildJsonObject {
            put("block_id", block.id.toString()); put("cursor", readCursor)
        })
        assertEquals("STALE", stale.getValue("status").jsonPrimitive.content)
        val second = base.copy(id = Uuid.random(), sources = c.messageNodes.subList(2, 4).map {
            ContextBlockSource(it.id, it.currentMessage.id)
        }, sourceHash = contextSourceHash(c.currentMessages.subList(2, 4)),
            prefixHash = contextSourceHash(c.currentMessages.take(4)), summary = "needle second")
        var searchable = listOf(block, second)
        fun searchTool() = createContextTools(c.assistantId, c.id, { c }, { searchable }).first()
        val page = searchTool().call(buildJsonObject { put("query", "needle"); put("limit", 1) })
        val searchCursor = page.getValue("next_cursor").jsonPrimitive.content
        searchable = listOf(block.copy(summary = "new needle"), second)
        assertEquals("STALE", searchTool().call(buildJsonObject {
            put("query", "needle"); put("limit", 1); put("cursor", searchCursor)
        }).getValue("status").jsonPrimitive.content)
    }

    @Test fun `bad input never coerces arrays or wrong types`() = runBlocking {
        val (c, b) = fixture()
        val tool = createContextTools(c.assistantId, c.id, { c }, { listOf(b) }).first()
        assertEquals("BAD_ARGUMENT", tool.call(buildJsonObject { put("query", buildJsonArray { add("x") }) }).getValue("status").jsonPrimitive.content)
        assertEquals("BAD_ARGUMENT", tool.call(buildJsonObject { put("query", "x"); put("scope", 3) }).getValue("status").jsonPrimitive.content)
    }
}
