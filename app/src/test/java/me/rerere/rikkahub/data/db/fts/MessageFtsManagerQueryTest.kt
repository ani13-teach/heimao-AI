package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFtsManagerQueryTest {
    @Test
    fun `global search has no assistant restriction`() {
        val statement = buildMessageSearchStatement(
            keyword = "project",
            sort = MessageSearchSort.RELEVANCE,
            assistantId = null,
        )

        assertEquals(listOf("project"), statement.args)
        assertFalse(statement.sql.contains("assistant_id = ?"))
    }

    @Test
    fun `assistant search correlates FTS rows to the requested assistant`() {
        val statement = buildMessageSearchStatement(
            keyword = "project",
            sort = MessageSearchSort.NEWEST_FIRST,
            assistantId = "assistant-a",
        )

        assertEquals(listOf("project", "assistant-a"), statement.args)
        assertTrue(statement.sql.contains("conversationentity.id = message_fts.conversation_id"))
        assertTrue(statement.sql.contains("conversationentity.assistant_id = ?"))
        assertTrue(statement.sql.contains("ORDER BY update_at DESC, rank"))
    }
}
