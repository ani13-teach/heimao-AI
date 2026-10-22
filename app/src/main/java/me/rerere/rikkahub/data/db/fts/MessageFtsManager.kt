package me.rerere.rikkahub.data.db.fts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String?,
    val messageId: String?,
    val conversationId: String,
    val conversationNumber: Long,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("search_rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, search_rank"),
    OLDEST_FIRST("update_at ASC, search_rank"),
}

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
            val insert = db.compileStatement(
                "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            )
            try {
                conversation.messageNodes.forEach { node ->
                    node.messages.forEach { message ->
                        val text = message.extractFtsText()
                        if (text.isNotBlank()) {
                            insert.clearBindings()
                            insert.bindString(1, text)
                            insert.bindString(2, node.id.toString())
                            insert.bindString(3, message.id.toString())
                            insert.bindString(4, conversationId)
                            insert.bindString(5, conversation.title)
                            insert.bindLong(6, conversation.updateAt.toEpochMilli())
                            insert.executeInsert()
                        }
                    }
                }
            } finally {
                insert.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        limit: Int = 50,
        assistantId: String? = null,
    ): List<MessageSearchResult> = searchInternal(
        keyword = keyword,
        sort = sort,
        assistantId = assistantId,
        excludeConversationId = null,
        limit = limit,
        includeTitles = true,
    )

    suspend fun searchForAssistant(
        assistantId: String,
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        limit: Int = 50,
        excludeConversationId: String? = null,
    ): List<MessageSearchResult> {
        require(assistantId.isNotBlank()) { "Assistant id is required" }
        return searchInternal(keyword, sort, assistantId, excludeConversationId, limit)
    }

    private suspend fun searchInternal(
        keyword: String,
        sort: MessageSearchSort,
        assistantId: String?,
        excludeConversationId: String?,
        limit: Int,
        includeTitles: Boolean = false,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val statement = buildMessageSearchStatement(
            keyword = keyword,
            sort = sort,
            assistantId = assistantId,
            excludeConversationId = excludeConversationId,
            limit = limit,
            includeTitles = includeTitles,
        )
        val cursor = db.query(statement.sql, statement.args.toTypedArray())
        cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        MessageSearchResult(
                            nodeId = if (it.isNull(0)) null else it.getString(0),
                            messageId = if (it.isNull(1)) null else it.getString(1),
                            conversationId = it.getString(2),
                            conversationNumber = it.getLong(3),
                            title = it.getString(4),
                            updateAt = Instant.ofEpochMilli(it.getLong(5)),
                            snippet = it.getString(6),
                        )
                    )
                }
            }
        }
    }
}

internal data class MessageSearchStatement(
    val sql: String,
    val args: List<Any?>,
)

internal fun buildMessageSearchStatement(
    keyword: String,
    sort: MessageSearchSort,
    assistantId: String?,
    excludeConversationId: String? = null,
    limit: Int = 50,
    includeTitles: Boolean = false,
): MessageSearchStatement {
    val normalizedKeyword = keyword.trim()
    require(normalizedKeyword.isNotEmpty()) { "Search query must not be blank" }
    require(normalizedKeyword.length <= 200) { "Search query is too long" }
    require(limit in 1..100) { "Search limit must be between 1 and 100" }
    require(assistantId != null || excludeConversationId == null) {
        "A global search cannot exclude a private assistant conversation"
    }

    val assistantFilter = if (assistantId == null) "" else "AND conversationentity.assistant_id = ?"
    val exclusionFilter = if (excludeConversationId == null) "" else "AND message_fts.conversation_id != ?"
    val baseSelect = """
        SELECT message_fts.node_id AS node_id,
               message_fts.message_id AS message_id,
               message_fts.conversation_id AS conversation_id,
               conversationentity.conversation_number AS conversation_number,
               message_fts.title AS title,
               message_fts.update_at AS update_at,
               simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet,
               rank AS search_rank
        FROM message_fts
        INNER JOIN conversationentity
                ON conversationentity.id = message_fts.conversation_id
        WHERE message_fts.text MATCH jieba_query(?)
        $assistantFilter
        $exclusionFilter
    """.trimIndent()

    require(!includeTitles || excludeConversationId == null) {
        "App search cannot exclude a conversation"
    }

    val sql = if (includeTitles) {
        // Keep FTS ranking/snippets inside MATCH; a title hit needs no indexed message.
        """
            WITH body_matches AS MATERIALIZED (
                $baseSelect
            )
            SELECT * FROM body_matches
            UNION ALL
            SELECT NULL AS node_id, NULL AS message_id,
                   conversationentity.id AS conversation_id,
                   conversationentity.conversation_number AS conversation_number,
                   conversationentity.title AS title,
                   conversationentity.update_at AS update_at,
                   conversationentity.title AS snippet,
                   0.0 AS search_rank
            FROM conversationentity
            WHERE conversationentity.title LIKE '%' || ? || '%'
            $assistantFilter
              AND NOT EXISTS (
                  SELECT 1 FROM body_matches
                  WHERE body_matches.conversation_id = conversationentity.id
              )
            ORDER BY ${sort.orderBy}
            LIMIT ?
        """.trimIndent()
    } else if (assistantId == null) {
        """
            $baseSelect
            ORDER BY ${sort.orderBy}
            LIMIT ?
        """.trimIndent()
    } else {
        """
            WITH ranked_matches AS (
                SELECT matched.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY conversation_id
                           ORDER BY search_rank, update_at DESC
                       ) AS conversation_row
                FROM (
                    $baseSelect
                ) AS matched
            )
            SELECT node_id, message_id, conversation_id, conversation_number,
                   title, update_at, snippet
            FROM ranked_matches
            WHERE conversation_row = 1
            ORDER BY ${sort.orderBy}
            LIMIT ?
        """.trimIndent()
    }

    return MessageSearchStatement(
        sql = sql,
        args = buildList {
            add(normalizedKeyword)
            if (assistantId != null) add(assistantId)
            if (excludeConversationId != null) add(excludeConversationId)
            if (includeTitles) {
                add(normalizedKeyword)
                if (assistantId != null) add(assistantId)
            }
            add(limit)
        },
    )
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
