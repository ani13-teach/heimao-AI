package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.ContextBlock
import me.rerere.rikkahub.data.model.ContextBlockSource
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GenerationMessageSnapshot
import me.rerere.rikkahub.data.model.commitGenerationMessages
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ContextBlockRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenerationConversationCommitState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class GenerationConversationDeletionTest {
    private suspend fun withRepository(test: suspend (AppDatabase, ConversationRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = AppScope()
        try {
            // Search is not under test; provide the columns used by indexing without a tokenizer dependency.
            withContext(Dispatchers.IO) {
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TABLE message_fts(text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT, title TEXT, update_at INTEGER)"
                )
            }
            val repository = ConversationRepository(
                db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
                FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db),
            )
            test(db, repository)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    private fun fresh() = Conversation(
        assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("original").toMessageNode()),
        newConversation = true,
    )

    private fun block(conversation: Conversation) = ContextBlock(
        conversationId = conversation.id, assistantId = conversation.assistantId,
        sources = listOf(ContextBlockSource(conversation.messageNodes.first().id, conversation.currentMessages.first().id)),
        sourceHash = "source", prefixHash = "prefix", summary = "summary",
    )

    @Test fun firstUnsavedCommitWorksButDeletedConversationCannotBeReinserted() = runBlocking {
        withRepository { db, repository ->
            val conversation = fresh()
            val state = GenerationConversationCommitState(initiallyPersisted = false)
            val summary = block(conversation)
            val saved = repository.commitGenerationConversation(conversation, state, listOf(summary))
            assertTrue(saved.conversationNumber > 0)
            assertTrue(state.requiresExisting)
            assertEquals(listOf(summary), ContextBlockRepository(db).getBlocks(conversation.id))
            db.conversationDao().delete(repository.conversationToConversationEntity(saved))
            assertTrue(runCatching { repository.commitGenerationConversation(saved, state, listOf(summary)) }.isFailure)
            assertFalse(repository.existsConversationById(conversation.id))
            assertTrue(db.messageNodeDao().getNodesOfConversation(conversation.id.toString()).isEmpty())
            assertTrue(ContextBlockRepository(db).getBlocks(conversation.id).isEmpty())
        }
    }

    @Test fun persistedGenerationAndRegularUpdateBothRejectMissingConversation() = runBlocking {
        withRepository { db, repository ->
            val conversation = fresh()
            assertTrue(runCatching {
                repository.commitGenerationConversation(conversation, GenerationConversationCommitState(true))
            }.isFailure)
            assertTrue(runCatching { repository.updateConversation(conversation) }.isFailure)
            assertFalse(repository.existsConversationById(conversation.id))
            assertTrue(db.messageNodeDao().getNodesOfConversation(conversation.id.toString()).isEmpty())
        }
    }

    @Test fun changedOwnerAndConflictingBlockRollBackOriginals() = runBlocking {
        withRepository { db, repository ->
            val original = fresh()
            val state = GenerationConversationCommitState(false)
            val summary = block(original)
            val saved = repository.commitGenerationConversation(original, state, listOf(summary))
            assertTrue(runCatching {
                repository.commitGenerationConversation(saved.copy(assistantId = Uuid.random()), state)
            }.isFailure)
            val edited = saved.copy(title = "must roll back", messageNodes = listOf(UIMessage.user("changed").toMessageNode()))
            assertTrue(runCatching {
                repository.commitGenerationConversation(edited, state, listOf(summary.copy(summary = "conflicting")))
            }.isFailure)
            val reloaded = repository.getConversationById(saved.id)!!
            assertEquals(saved.title, reloaded.title)
            assertEquals(saved.assistantId, reloaded.assistantId)
            assertEquals(saved.messageNodes, reloaded.messageNodes)
            assertEquals(saved.conversationNumber, reloaded.conversationNumber)
            assertEquals(listOf(summary), ContextBlockRepository(db).getBlocks(saved.id))
        }
    }

    @Test fun batchDeletionRejectsEveryStaleGenerationCheckpoint() = runBlocking {
        withRepository { db, repository ->
            val first = fresh()
            val conversations = listOf(first, fresh().copy(assistantId = first.assistantId))
            val saved = conversations.map { conversation ->
                val state = GenerationConversationCommitState(false)
                repository.commitGenerationConversation(conversation, state, listOf(block(conversation))) to state
            }
            db.conversationDao().deleteAll()
            saved.forEach { (conversation, state) ->
                assertTrue(runCatching { repository.commitGenerationConversation(conversation, state) }.isFailure)
                assertFalse(repository.existsConversationById(conversation.id))
                assertTrue(db.messageNodeDao().getNodesOfConversation(conversation.id.toString()).isEmpty())
                assertTrue(ContextBlockRepository(db).getBlocks(conversation.id).isEmpty())
            }
        }
    }

    @Test fun deletionDuringNonCancellableCheckpointCompensationNeverReinserts() = runBlocking {
        withRepository { db, repository ->
            val original = fresh()
            val state = MutableStateFlow(original)
            val commitState = GenerationConversationCommitState(false)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var mainRequestSent = false
            var attempts = 0
            val generation = launch {
                runCatching {
                    commitGenerationMessages(state, GenerationMessageSnapshot.capture(original), original.currentMessages) { value ->
                        attempts++
                        repository.commitGenerationConversation(value, commitState).also {
                            if (attempts == 1) {
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                    }
                    mainRequestSent = true
                }
            }
            entered.await()
            val entity = db.conversationDao().getConversationById(original.id.toString())!!
            db.conversationDao().delete(entity)
            generation.cancel()
            release.complete(Unit)
            generation.join()
            assertEquals(2, attempts)
            assertFalse(mainRequestSent)
            assertFalse(repository.existsConversationById(original.id))
            assertTrue(db.messageNodeDao().getNodesOfConversation(original.id.toString()).isEmpty())
            assertEquals(original, state.value)
        }
    }
}
