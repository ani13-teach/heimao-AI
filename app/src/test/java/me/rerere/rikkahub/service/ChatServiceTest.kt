package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.MessageNodeContentVersion
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `new conversation captures assistant workspace during initialization`() {
        val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val assistantId = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val workspaceId = Uuid.parse("33333333-3333-3333-3333-333333333333")
        val assistant = Assistant(id = assistantId, workspaceId = workspaceId)

        val initialized = resolveConversationForInitialization(
            conversationId = conversationId,
            persistedConversation = null,
            assistant = assistant,
        )

        assertEquals(conversationId, initialized.id)
        assertEquals(assistantId, initialized.assistantId)
        assertEquals(workspaceId, initialized.workspaceId)
        assertTrue(initialized.newConversation)
    }

    @Test
    fun `existing conversation does not inherit current assistant workspace`() {
        val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val assistantId = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val workspaceId = Uuid.parse("33333333-3333-3333-3333-333333333333")
        val persisted = Conversation(
            id = conversationId,
            assistantId = assistantId,
            messageNodes = emptyList(),
            workspaceId = null,
        )

        val initialized = resolveConversationForInitialization(
            conversationId = conversationId,
            persistedConversation = persisted,
            assistant = Assistant(id = assistantId, workspaceId = workspaceId),
        )

        assertEquals(persisted, initialized)
        assertNull(initialized.workspaceId)
    }

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `summary context message wraps historical data as read-only evidence`() {
        val message = summaryContextMessage("source content")
        val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

        assertTrue(text.contains("BEGIN HISTORICAL CONVERSATION DATA"))
        assertTrue(text.contains("source content"))
        assertTrue(text.contains("END HISTORICAL CONVERSATION DATA"))
    }

    @Test
    fun `summary source ids are normalized with duplicates removed in first-seen order`() {
        val idA = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val idB = Uuid.parse("22222222-2222-2222-2222-222222222222")

        assertEquals(listOf(idA, idB), normalizeSummarySourceIds(listOf(idA, idB, idA)))
        assertEquals(listOf(idB, idA), normalizeSummarySourceIds(listOf(idB, idA, idB, idA)))
        assertEquals(emptyList<Uuid>(), normalizeSummarySourceIds(emptyList()))
        assertEquals(listOf(idA), normalizeSummarySourceIds(listOf(idA, idA, idA)))
    }

    @Test
    fun `summary sources are unchanged when updateAt and content version match`() {
        val idA = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val idB = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val read = listOf(conversation(idA, Instant.ofEpochMilli(1_000)), conversation(idB, Instant.ofEpochMilli(2_000)))
        val latest = listOf(conversation(idA, Instant.ofEpochMilli(1_000)), conversation(idB, Instant.ofEpochMilli(2_000)))

        assertFalse(summarySourcesMayHaveChanged(read, latest, readVersion = version(100), latestVersion = version(100)))
    }

    @Test
    fun `summary sources are changed when serialized length differs`() {
        val idA = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val read = listOf(conversation(idA, Instant.ofEpochMilli(1_000)))
        val latest = listOf(conversation(idA, Instant.ofEpochMilli(1_000)))

        assertTrue(summarySourcesMayHaveChanged(read, latest, readVersion = version(100), latestVersion = version(200)))
    }

    @Test
    fun `summary sources are changed when select index sum differs even with equal length`() {
        // 分支切换（selectMessageNode）只改 select_index：消息长度与 updateAt 都可能不变，
        // 版本必须仍能识别变化，否则会缓存到旧分支对应的 summaryContext。
        val idA = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val read = listOf(conversation(idA, Instant.ofEpochMilli(1_000)))
        val latest = listOf(conversation(idA, Instant.ofEpochMilli(1_000)))

        assertTrue(
            summarySourcesMayHaveChanged(
                read,
                latest,
                readVersion = version(serializedLength = 100, selectIndexSum = 0),
                latestVersion = version(serializedLength = 100, selectIndexSum = 2),
            )
        )
    }

    @Test
    fun `summary sources are changed when node count differs`() {
        val idA = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val read = listOf(conversation(idA, Instant.ofEpochMilli(1_000)))
        val latest = listOf(conversation(idA, Instant.ofEpochMilli(1_000)))

        assertTrue(
            summarySourcesMayHaveChanged(
                read,
                latest,
                readVersion = version(serializedLength = 100, nodeCount = 5),
                latestVersion = version(serializedLength = 100, nodeCount = 6),
            )
        )
    }

    @Test
    fun `summary sources are changed when updateAt differs even within the same millisecond window`() {
        val idA = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val read = listOf(conversation(idA, Instant.ofEpochMilli(1_000)))
        val latest = listOf(conversation(idA, Instant.ofEpochMilli(1_001)))

        assertTrue(summarySourcesMayHaveChanged(read, latest, readVersion = version(100), latestVersion = version(100)))
    }

    @Test
    fun `summary sources are changed when a conversation is missing from the latest snapshot`() {
        val idA = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val idB = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val read = listOf(conversation(idA, Instant.ofEpochMilli(1_000)), conversation(idB, Instant.ofEpochMilli(2_000)))
        val latest = listOf(conversation(idA, Instant.ofEpochMilli(1_000)))

        assertTrue(summarySourcesMayHaveChanged(read, latest, readVersion = version(100), latestVersion = version(100)))
    }

    @Test
    fun `invalid mcp server names filter empty and non alphanumeric names`() {
        assertEquals(listOf("", "my-server", "server 1", "服务器"), invalidMcpServerNames(listOf("ok", "", "my-server", "server 1", "服务器", "ok")))
    }

    @Test
    fun `invalid mcp server names keep distinct first-seen order`() {
        assertEquals(listOf("bad-name", "bad_name"), invalidMcpServerNames(listOf("bad-name", "good", "bad_name", "bad-name")))
    }

    @Test
    fun `invalid mcp server names accepts alphanumeric names`() {
        assertEquals(emptyList<String>(), invalidMcpServerNames(listOf("alpha", "Beta1", "GAMMA", "a1B2")))
    }

    @Test
    fun `invalid mcp server names handles empty input`() {
        assertEquals(emptyList<String>(), invalidMcpServerNames(emptyList()))
    }

    private fun version(
        serializedLength: Long = 0,
        selectIndexSum: Long = 0,
        nodeCount: Long = 0,
    ) = MessageNodeContentVersion(
        serializedLength = serializedLength,
        selectIndexSum = selectIndexSum,
        nodeCount = nodeCount,
    )

    private fun conversation(id: Uuid, updateAt: Instant): Conversation = Conversation(
        id = id,
        assistantId = Uuid.parse("33333333-3333-3333-3333-333333333333"),
        messageNodes = emptyList(),
        updateAt = updateAt,
    )
}
