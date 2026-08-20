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
