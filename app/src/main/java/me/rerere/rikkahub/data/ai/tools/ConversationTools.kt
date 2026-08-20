package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.summary.visibleSummaryText
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate
import kotlin.uuid.Uuid

private const val MAX_SEARCH_QUERY_CHARACTERS = 200
private const val MAX_READ_CONTEXT_CHARACTERS = 12_000
private const val MAX_READ_MESSAGE_CHARACTERS = 4_000

/** Tools that query only the current assistant's history on demand. */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    currentConversationId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            List recent conversations with this assistant. Only lightweight metadata is returned,
            ordered by pinned first and then most recently updated. Use conversation_search for content.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum conversations to return (default: 10, max: 30)")
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = conversationRepo.getRecentConversations(assistantId, limit)
            val payload = buildJsonArray {
                recent.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("number", conversation.conversationNumber)
                        put("title", conversation.title.ifBlank { "Untitled" })
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_search",
        description = """
            Full-text search across this assistant's past conversations. Other assistants and the
            current conversation are excluded. Results are snippets; use conversation_read with the
            returned IDs when bounded surrounding context is needed.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Focused keywords, up to 200 characters")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum distinct conversations (default: 15, max: 50)")
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
                ?: error("query is required")
            require(query.length <= MAX_SEARCH_QUERY_CHARACTERS) { "query is too long" }
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
            val results = conversationRepo.searchMessagesOfAssistant(
                assistantId = assistantId,
                keyword = query,
                sort = MessageSearchSort.RELEVANCE,
                limit = limit,
                excludeConversationId = currentConversationId,
            )
            val payload = buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("conversation_id", result.conversationId)
                        put("conversation_number", result.conversationNumber)
                        put("node_id", result.nodeId)
                        put("message_id", result.messageId)
                        put("title", result.title.ifBlank { "Untitled" })
                        put("snippet", result.snippet)
                        put("date", result.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_read",
        description = """
            Read a small visible-text window around one conversation_search hit. The conversation
            must belong to this assistant. System messages, reasoning, tool output, and attachment
            bodies are excluded. This tool cannot read a whole conversation.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("conversation_id", buildJsonObject { put("type", "string") })
                    put("node_id", buildJsonObject { put("type", "string") })
                    put("message_id", buildJsonObject { put("type", "string") })
                    put("radius", buildJsonObject {
                        put("type", "integer")
                        put("description", "Messages before and after the hit (default: 2, max: 5)")
                    })
                },
                required = listOf("conversation_id", "node_id", "message_id")
            )
        },
        execute = { input ->
            fun requiredUuid(name: String): Uuid {
                val value = input.jsonObject[name]?.jsonPrimitive?.contentOrNull
                    ?: error("$name is required")
                return runCatching { Uuid.parse(value) }
                    .getOrElse { error("$name is invalid") }
            }

            val messages = conversationRepo.readConversationMessageWindow(
                assistantId = assistantId,
                conversationId = requiredUuid("conversation_id"),
                nodeId = requiredUuid("node_id"),
                messageId = requiredUuid("message_id"),
                radius = (input.jsonObject["radius"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(0, 5),
            )
            var remaining = MAX_READ_CONTEXT_CHARACTERS
            val payload = buildJsonArray {
                messages.forEach { message ->
                    if (remaining <= 0) return@forEach
                    if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) {
                        return@forEach
                    }
                    val text = message.visibleSummaryText()
                        .take(MAX_READ_MESSAGE_CHARACTERS)
                        .take(remaining)
                    if (text.isNotBlank()) {
                        add(buildJsonObject {
                            put("message_id", message.id.toString())
                            put("role", message.role.name.lowercase())
                            put("text", text)
                        })
                        remaining -= text.length
                    }
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
)
