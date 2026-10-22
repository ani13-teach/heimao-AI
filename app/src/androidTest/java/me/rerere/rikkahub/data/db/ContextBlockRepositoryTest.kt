package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.ContextBlock
import me.rerere.rikkahub.data.model.ContextBlockSource
import me.rerere.rikkahub.data.repository.ContextBlockRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ContextBlockRepositoryTest {
    @Test fun blocksRoundTripSurviveNodeReplacementAndCascadeOnlyWithConversation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val conversation = ConversationEntity(
                id = Uuid.random().toString(), assistantId = Uuid.random().toString(),
                title = "test", nodes = "[]", createAt = 1, updateAt = 1,
                chatSuggestions = "[]", isPinned = false, conversationNumber = 1,
            )
            db.conversationDao().insert(conversation)
            val node = MessageNodeEntity(Uuid.random().toString(), conversation.id, 0, "[]", 0)
            db.messageNodeDao().insertAll(listOf(node))
            val repository = ContextBlockRepository(db)
            val block = ContextBlock(
                conversationId = Uuid.parse(conversation.id), assistantId = Uuid.parse(conversation.assistantId),
                sources = listOf(ContextBlockSource(Uuid.parse(node.id), Uuid.random())),
                sourceHash = "source", prefixHash = "prefix", summary = "summary",
            )
            repository.insertBlocks(listOf(block))
            assertEquals(listOf(block), repository.getBlocks(block.conversationId))
            db.messageNodeDao().deleteByConversation(conversation.id)
            db.messageNodeDao().insertAll(listOf(node))
            assertEquals(listOf(block), repository.getBlocks(block.conversationId))
            val fresh = block.copy(id = Uuid.random())
            assertTrue(runCatching { repository.insertBlocks(listOf(fresh, block)) }.isFailure)
            assertEquals(listOf(block), repository.getBlocks(block.conversationId))
            assertTrue(runCatching { repository.insertBlocks(listOf(fresh.copy(assistantId = Uuid.random()))) }.isFailure)
            assertEquals(listOf(block), repository.getBlocks(block.conversationId))
            db.conversationDao().delete(conversation)
            assertTrue(repository.getBlocks(block.conversationId).isEmpty())
        } finally {
            db.close()
        }
    }
}
