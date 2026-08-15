package me.rerere.rikkahub.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSummaryDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ConversationDAO

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.conversationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun numberLookupNeverCrossesAssistantBoundary() = runBlocking {
        dao.insert(conversation(id = "conversation-a", assistantId = "assistant-a", number = 1))
        dao.insert(conversation(id = "conversation-b", assistantId = "assistant-b", number = 2))

        val results = dao.getConversationsByNumbersOfAssistant(
            assistantId = "assistant-a",
            numbers = listOf(1L, 2L),
        )

        assertEquals(listOf("conversation-a"), results.map { it.id })
        assertEquals("conversation-b", dao.getConversationIdByNumber(2))
    }

    @Test
    fun duplicateConversationNumberIsRejected() = runBlocking {
        dao.insert(conversation(id = "conversation-a", assistantId = "assistant-a", number = 1))

        val result = runCatching {
            dao.insert(conversation(id = "conversation-b", assistantId = "assistant-b", number = 1))
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun sequenceStartsAfterExistingNumbersAndOnlyMovesForward() = runBlocking {
        val existing = conversation(id = "conversation-a", assistantId = "assistant-a", number = 7)
        dao.insert(existing)
        dao.ensureConversationNumberSequence()

        assertEquals(8L, dao.getNextConversationNumber())
        dao.delete(existing)
        dao.ensureConversationNumberSequence()
        assertEquals(8L, dao.getNextConversationNumber())
        dao.ensureNextConversationNumber(5)
        assertEquals(8L, dao.getNextConversationNumber())
        dao.incrementConversationNumber()
        assertEquals(9L, dao.getNextConversationNumber())
    }

    @Test
    fun emptyDatabaseHasNoSequenceUntilInitialized() = runBlocking {
        assertNull(dao.getNextConversationNumber())
        dao.ensureConversationNumberSequence()
        assertEquals(1L, dao.getNextConversationNumber())
    }

    private fun conversation(
        id: String,
        assistantId: String,
        number: Long,
    ) = ConversationEntity(
        id = id,
        assistantId = assistantId,
        title = id,
        nodes = "[]",
        createAt = number,
        updateAt = number,
        chatSuggestions = "[]",
        isPinned = false,
        conversationNumber = number,
    )
}
