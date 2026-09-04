package me.rerere.rikkahub.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
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
    fun legacyWorkspaceConsumptionIsAtomicAndRetrySafe() = runBlocking {
        dao.insert(
            conversation(id = "conversation-a", assistantId = "assistant-a", number = 1).copy(
                workspaceId = "workspace-a",
                workspaceCwd = "/workspace/project",
            )
        )
        dao.insert(
            conversation(id = "conversation-b", assistantId = "assistant-b", number = 2).copy(
                workspaceId = "workspace-b",
                workspaceCwd = "/workspace/other",
            )
        )

        val bindings = mapOf("assistant-a" to "workspace-a", "assistant-b" to null)
        dao.consumeAllLegacyWorkspaceReferences(bindings)
        dao.consumeAllLegacyWorkspaceReferences(bindings)

        val conversationA = dao.getConversationById("conversation-a")!!
        assertNull(conversationA.workspaceId)
        assertEquals("/workspace/project", conversationA.workspaceCwd)
        val conversationB = dao.getConversationById("conversation-b")!!
        assertNull(conversationB.workspaceId)
        assertEquals("", conversationB.workspaceCwd)
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
    fun lightweightSummaryLookupIsAssistantScoped() = runBlocking {
        dao.insert(
            conversation(
                id = "conversation-a",
                assistantId = "assistant-a",
                number = 1,
                summaryContext = "x".repeat(100_000),
            )
        )
        dao.insert(conversation(id = "conversation-b", assistantId = "assistant-b", number = 2))

        val results = dao.getConversationSummariesByIdsOfAssistant(
            assistantId = "assistant-a",
            ids = listOf("conversation-b", "conversation-a"),
        )

        assertEquals(listOf("conversation-a"), results.map { it.id })
        assertEquals(listOf(1L), results.map { it.conversationNumber })
        assertEquals(listOf("conversation-a"), results.map { it.title })
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

    @Test
    fun messageNodeContentVersionTracksSerializedLengthSelectIndexAndNodeCount() = runBlocking {
        dao.insert(conversation(id = "conversation-a", assistantId = "assistant-a", number = 1))
        dao.insert(conversation(id = "conversation-b", assistantId = "assistant-b", number = 2))
        val nodeDao = database.messageNodeDao()
        nodeDao.insertAll(
            listOf(
                MessageNodeEntity(
                    id = "node-a1",
                    conversationId = "conversation-a",
                    nodeIndex = 0,
                    messages = "[\"m1\"]",
                    selectIndex = 0,
                ),
                MessageNodeEntity(
                    id = "node-a2",
                    conversationId = "conversation-a",
                    nodeIndex = 1,
                    messages = "[\"m2\"]",
                    selectIndex = 2,
                ),
                MessageNodeEntity(
                    id = "node-b1",
                    conversationId = "conversation-b",
                    nodeIndex = 0,
                    messages = "[\"m3\"]",
                    selectIndex = 1,
                ),
            )
        )

        val versionA = nodeDao.getMessageNodeContentVersion(listOf("conversation-a"))
        assertEquals(MessageNodeContentVersion(serializedLength = 12L, selectIndexSum = 2L, nodeCount = 2L), versionA)

        val versionAB = nodeDao.getMessageNodeContentVersion(listOf("conversation-a", "conversation-b"))
        assertEquals(MessageNodeContentVersion(serializedLength = 18L, selectIndexSum = 3L, nodeCount = 3L), versionAB)

        // 分支切换只改 select_index：长度不变，版本必须变化
        nodeDao.update(
            nodeDao.getNodeByIdOfConversation("node-a2", "conversation-a")!!.copy(selectIndex = 0)
        )
        val versionASwitched = nodeDao.getMessageNodeContentVersion(listOf("conversation-a"))
        assertEquals(MessageNodeContentVersion(serializedLength = 12L, selectIndexSum = 0L, nodeCount = 2L), versionASwitched)

        assertEquals(MessageNodeContentVersion.EMPTY, nodeDao.getMessageNodeContentVersion(emptyList()))
    }

    private fun conversation(
        id: String,
        assistantId: String,
        number: Long,
        summaryContext: String = "",
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
        summaryContext = summaryContext,
    )
}
