package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFtsManagerQueryTest {
    @Test
    fun `global search has no assistant restriction and binds limit`() {
        val statement = buildMessageSearchStatement(
            keyword = "project",
            sort = MessageSearchSort.RELEVANCE,
            assistantId = null,
            limit = 12,
        )

        assertEquals(listOf("project", 12), statement.args)
        assertFalse(statement.sql.contains("assistant_id = ?"))
        assertFalse(statement.sql.contains("ROW_NUMBER()"))
        assertTrue(statement.sql.contains("LIMIT ?"))
    }

    @Test
    fun `assistant search scopes ownership excludes current and deduplicates conversations`() {
        val statement = buildMessageSearchStatement(
            keyword = " project ",
            sort = MessageSearchSort.NEWEST_FIRST,
            assistantId = "assistant-a",
            excludeConversationId = "conversation-current",
            limit = 7,
        )

        assertEquals(listOf("project", "assistant-a", "conversation-current", 7), statement.args)
        assertTrue(statement.sql.contains("conversationentity.id = message_fts.conversation_id"))
        assertTrue(statement.sql.contains("conversationentity.assistant_id = ?"))
        assertTrue(statement.sql.contains("message_fts.conversation_id != ?"))
        assertTrue(statement.sql.contains("PARTITION BY conversation_id"))
        assertTrue(statement.sql.contains("ORDER BY update_at DESC, search_rank"))
    }

    @Test
    fun `app search matches titles and bodies without conversation deduplication`() {
        val statement = buildMessageSearchStatement(
            keyword = " project ",
            sort = MessageSearchSort.RELEVANCE,
            assistantId = "assistant-a",
            limit = 12,
            includeTitles = true,
        )

        assertEquals(listOf("project", "assistant-a", "project", "assistant-a", 12), statement.args)
        assertEquals(5, statement.sql.count { it == '?' })
        assertTrue(statement.sql.contains("message_fts.text MATCH jieba_query(?)"))
        assertTrue(statement.sql.contains("conversationentity.title LIKE '%' || ? || '%'"))
        assertTrue(statement.sql.contains("WITH body_matches AS MATERIALIZED"))
        assertTrue(statement.sql.contains("NOT EXISTS"))
        assertTrue(statement.sql.contains("NULL AS node_id, NULL AS message_id"))
        assertEquals(2, Regex("conversationentity.assistant_id = \\?").findAll(statement.sql).count())
        assertFalse(statement.sql.contains("ROW_NUMBER()"))
    }

    @Test
    fun `all assistants app search retains sorting and global limit`() {
        val statement = buildMessageSearchStatement(
            keyword = "中文",
            sort = MessageSearchSort.OLDEST_FIRST,
            assistantId = null,
            limit = 9,
            includeTitles = true,
        )

        assertEquals(listOf("中文", "中文", 9), statement.args)
        assertFalse(statement.sql.contains("assistant_id = ?"))
        assertTrue(statement.sql.contains("ORDER BY update_at ASC, search_rank"))
    }

    @Test
    fun `AI search remains body only`() {
        val statement = buildMessageSearchStatement(
            keyword = "project",
            sort = MessageSearchSort.RELEVANCE,
            assistantId = "assistant-a",
        )

        assertFalse(statement.sql.contains("title LIKE"))
        assertFalse(statement.sql.contains("UNION ALL"))
        assertTrue(statement.sql.contains("PARTITION BY conversation_id"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank search is rejected before database query`() {
        buildMessageSearchStatement(
            keyword = "   ",
            sort = MessageSearchSort.RELEVANCE,
            assistantId = "assistant-a",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized limit is rejected`() {
        buildMessageSearchStatement(
            keyword = "project",
            sort = MessageSearchSort.RELEVANCE,
            assistantId = null,
            limit = 101,
        )
    }
}
