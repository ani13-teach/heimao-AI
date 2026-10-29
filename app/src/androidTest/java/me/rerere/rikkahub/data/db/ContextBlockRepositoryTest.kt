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
            val rolling = block.copy(id = Uuid.random(), formatVersion = 2,
                summary = "rolling summary", createdAt = block.createdAt + 1)
            val snapshots = listOf(block, rolling)
            repository.insertBlocks(snapshots)
            assertEquals(snapshots, repository.getBlocks(block.conversationId))
            assertEquals(listOf(1, 2), repository.getBlocks(block.conversationId).map { it.formatVersion })
            val otherConversation = conversation.copy(id = Uuid.random().toString(),
                assistantId = Uuid.random().toString(), conversationNumber = 2)
            db.conversationDao().insert(otherConversation)
            val otherBlock = rolling.copy(id = Uuid.random(), conversationId = Uuid.parse(otherConversation.id),
                assistantId = Uuid.parse(otherConversation.assistantId), summary = "other assistant")
            repository.insertBlocks(listOf(otherBlock))
            assertEquals(listOf(otherBlock), repository.getBlocks(otherBlock.conversationId))
            db.messageNodeDao().deleteByConversation(conversation.id)
            db.messageNodeDao().insertAll(listOf(node))
            assertEquals(snapshots, repository.getBlocks(block.conversationId))
            val fresh = block.copy(id = Uuid.random())
            assertTrue(runCatching { repository.insertBlocks(listOf(fresh, block)) }.isFailure)
            assertEquals(snapshots, repository.getBlocks(block.conversationId))
            assertTrue(runCatching { repository.insertBlocks(listOf(
                fresh, rolling.copy(id = Uuid.random(), assistantId = otherBlock.assistantId)
            )) }.isFailure)
            assertEquals(listOf(otherBlock), repository.getBlocks(otherBlock.conversationId))
            assertEquals(snapshots, repository.getBlocks(block.conversationId))
            db.conversationDao().delete(conversation)
            assertTrue(repository.getBlocks(block.conversationId).isEmpty())
            assertEquals(listOf(otherBlock), repository.getBlocks(otherBlock.conversationId))
        } finally {
            db.close()
        }
    }
}
