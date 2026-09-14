package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeContentVersion
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

internal fun mergeConversationForRegularUpdate(
    incoming: Conversation,
    existing: ConversationEntity,
): Conversation = incoming.copy(
    assistantId = Uuid.parse(existing.assistantId),
    folderId = existing.folderId.ifEmpty { null }?.let(Uuid::parse),
    conversationNumber = existing.conversationNumber,
    workspaceCwd = existing.workspaceCwd.ifEmpty { null },
)

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
        private const val SUMMARY_QUERY_BATCH_SIZE = 500
        private const val SUMMARY_MAX_SERIALIZED_SOURCE_CHARACTERS = 16_000_000L
    }

    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl))

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> =
        conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit,
        ).map(::conversationSummaryToConversation)

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> =
        conversationDAO.getConversationSummariesOfAssistant(assistantId.toString())
            .map { entities -> entities.map(::conversationSummaryToConversation) }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString()),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun getConversationSummariesOfAssistantByIds(
        assistantId: Uuid,
        conversationIds: List<Uuid>,
    ): List<Conversation> {
        if (conversationIds.isEmpty()) return emptyList()
        val summaries = getLightConversationEntitiesByIdsOfAssistant(
            assistantId = assistantId,
            conversationIds = conversationIds,
        ).associateBy { it.id }
        return conversationIds.mapNotNull { id ->
            summaries[id.toString()]?.let(::conversationSummaryToConversation)
        }
    }

    suspend fun getFullConversationsOfAssistantByIds(
        assistantId: Uuid,
        conversationIds: List<Uuid>,
        maxSerializedCharacters: Long = SUMMARY_MAX_SERIALIZED_SOURCE_CHARACTERS,
    ): List<Conversation> {
        if (conversationIds.isEmpty()) return emptyList()
        require(maxSerializedCharacters > 0) { "maxSerializedCharacters must be positive" }
        val entities = getConversationEntitiesByIdsOfAssistant(
            assistantId = assistantId,
            conversationIds = conversationIds,
        ).associateBy { it.id }
        val orderedEntities = conversationIds.mapNotNull { entities[it.toString()] }
        if (orderedEntities.size == conversationIds.size) {
            val serializedLength = messageNodeDAO.getSerializedLengthOfConversations(
                orderedEntities.map { it.id }
            )
            require(serializedLength <= maxSerializedCharacters) {
                "Conversation summary source exceeds the serialized size limit"
            }
        }
        return orderedEntities.map { entity ->
            conversationEntityToConversation(entity, loadMessageNodes(entity.id))
        }
    }

    /**
     * 轻量内容版本字段：指定会话消息节点的序列化总长度（SQL 聚合，不读取大内容）。
     * 用于 summaryContext 缓存写入前的一致性校验；等长内容替换无法区分，不作为内容指纹。
     */
    suspend fun getSerializedLengthOfConversations(conversationIds: List<Uuid>): Long {
        if (conversationIds.isEmpty()) return 0L
        return messageNodeDAO.getSerializedLengthOfConversations(conversationIds.map { it.toString() })
    }

    /**
     * 轻量内容版本指纹：指定会话消息节点的 [MessageNodeContentVersion]（SQL 聚合，不读取大内容）。
     * 除序列化总长度外还包含 select_index 之和与节点数：分支切换只改 select_index，
     * 长度与 update_at 都可能不变，必须纳入版本用于 summaryContext 缓存写入前的一致性校验；
     * 等长内容替换无法区分，不作为内容指纹。
     */
    suspend fun getMessageNodeContentVersion(conversationIds: List<Uuid>): MessageNodeContentVersion {
        if (conversationIds.isEmpty()) return MessageNodeContentVersion.EMPTY
        return messageNodeDAO.getMessageNodeContentVersion(conversationIds.map { it.toString() })
    }

    private suspend fun getLightConversationEntitiesByIdsOfAssistant(
        assistantId: Uuid,
        conversationIds: List<Uuid>,
    ): List<LightConversationEntity> = conversationIds
        .distinct()
        .chunked(SUMMARY_QUERY_BATCH_SIZE)
        .flatMap { batch ->
            conversationDAO.getConversationSummariesByIdsOfAssistant(
                assistantId = assistantId.toString(),
                ids = batch.map { it.toString() },
            )
        }

    private suspend fun getConversationEntitiesByIdsOfAssistant(
        assistantId: Uuid,
        conversationIds: List<Uuid>,
    ): List<ConversationEntity> = conversationIds
        .distinct()
        .chunked(SUMMARY_QUERY_BATCH_SIZE)
        .flatMap { batch ->
            conversationDAO.getConversationsByIdsOfAssistant(
                assistantId = assistantId.toString(),
                ids = batch.map { it.toString() },
            )
        }

    suspend fun createSummaryConversation(
        sourceAssistantId: Uuid,
        summarizerAssistantId: Uuid,
        sourceConversationIds: List<Uuid>,
        title: String,
    ): Conversation {
        val normalizedIds = sourceConversationIds.distinct()
        require(normalizedIds.isNotEmpty()) { "At least one source conversation is required" }
        val sources = getConversationSummariesOfAssistantByIds(sourceAssistantId, normalizedIds)
        require(sources.size == normalizedIds.size) {
            "One or more source conversations do not belong to the selected assistant"
        }

        val summaryConversation = Conversation(
            assistantId = summarizerAssistantId,
            title = title,
            messageNodes = emptyList(),
            summarySourceAssistantId = sourceAssistantId,
            summarySourceConversationIds = normalizedIds,
        )
        insertConversation(summaryConversation)
        return getConversationById(summaryConversation.id)
            ?: error("Failed to load created summary conversation")
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun insertConversation(conversation: Conversation): Conversation {
        val persistedConversation = database.withTransaction {
            val numberedConversation = assignConversationNumber(conversation.copy(workspaceCwd = null))
            conversationDAO.insert(
                conversationToConversationEntity(numberedConversation)
            )
            saveMessageNodes(numberedConversation.id.toString(), numberedConversation.messageNodes)
            numberedConversation
        }
        messageFtsManager.indexConversation(persistedConversation)
        return persistedConversation
    }

    suspend fun updateConversation(conversation: Conversation): Conversation {
        val persistedConversation = database.withTransaction {
            val existing = conversationDAO.getConversationById(conversation.id.toString())
            val numberedConversation = if (existing != null) {
                mergeConversationForRegularUpdate(conversation, existing)
            } else {
                assignConversationNumber(conversation.copy(workspaceCwd = null))
            }
            conversationDAO.update(
                conversationToConversationEntity(numberedConversation)
            )
            // 删除旧的节点，插入新的节点
            messageNodeDAO.deleteByConversation(numberedConversation.id.toString())
            saveMessageNodes(numberedConversation.id.toString(), numberedConversation.messageNodes)
            numberedConversation
        }
        messageFtsManager.indexConversation(persistedConversation)
        return persistedConversation
    }

    private suspend fun assignConversationNumber(conversation: Conversation): Conversation {
        conversationDAO.ensureConversationNumberSequence()
        if (conversation.conversationNumber > 0) {
            val numberOwner = conversationDAO.getConversationIdByNumber(conversation.conversationNumber)
            if (numberOwner == null || numberOwner == conversation.id.toString()) {
                require(conversation.conversationNumber < Long.MAX_VALUE) {
                    "Conversation number is too large"
                }
                conversationDAO.ensureNextConversationNumber(conversation.conversationNumber + 1)
                return conversation
            }
        }

        val number = conversationDAO.getNextConversationNumber()
            ?: throw IllegalStateException("Conversation number sequence is unavailable")
        require(number < Long.MAX_VALUE) { "Conversation number sequence is exhausted" }
        conversationDAO.incrementConversationNumber()
        return conversation.copy(conversationNumber = number)
    }

    suspend fun deleteConversation(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        filesManager.deleteChatFiles(fullConversation.files)
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        limit: Int = 50,
    ) = messageFtsManager.search(keyword, sort, limit)

    suspend fun searchMessagesOfAssistant(
        assistantId: Uuid,
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        limit: Int = 50,
        excludeConversationId: Uuid? = null,
    ) = messageFtsManager.searchForAssistant(
        assistantId = assistantId.toString(),
        keyword = keyword,
        sort = sort,
        limit = limit,
        excludeConversationId = excludeConversationId?.toString(),
    )

    suspend fun readConversationMessageWindow(
        assistantId: Uuid,
        conversationId: Uuid,
        nodeId: Uuid,
        messageId: Uuid,
        radius: Int,
    ): List<UIMessage> {
        require(radius in 0..5) { "radius must be between 0 and 5" }
        val conversation = conversationDAO.getConversationById(conversationId.toString())
            ?: error("Conversation not found")
        require(conversation.assistantId == assistantId.toString()) {
            "Conversation does not belong to the current assistant"
        }
        val node = messageNodeDAO.getNodeByIdOfConversation(
            nodeId = nodeId.toString(),
            conversationId = conversationId.toString(),
        ) ?: error("Conversation message node not found")
        val messages = JsonInstant.decodeFromString<List<UIMessage>>(node.messages)
        val index = messages.indexOfFirst { it.id == messageId }
        require(index >= 0) { "Conversation message not found" }
        val start = (index - radius).coerceAtLeast(0)
        val endExclusive = (index + radius + 1).coerceAtMost(messages.size)
        return messages.subList(start, endExclusive)
    }

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            workspaceId = null,
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            conversationNumber = conversation.conversationNumber,
            summarySourceAssistantId = conversation.summarySourceAssistantId?.toString() ?: "",
            summarySourceConversationIds = JsonInstant.encodeToString(
                conversation.summarySourceConversationIds.map { it.toString() }
            ),
            summaryContext = conversation.summaryContext,
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            conversationNumber = conversationEntity.conversationNumber,
            summarySourceAssistantId = conversationEntity.summarySourceAssistantId
                .ifEmpty { null }
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() },
            summarySourceConversationIds = runCatching {
                JsonInstant.decodeFromString<List<String>>(conversationEntity.summarySourceConversationIds)
                    .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            }.getOrDefault(emptyList()),
            summaryContext = conversationEntity.summaryContext,
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationSummaryToConversation(entity)
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            conversationNumber = entity.conversationNumber,
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    suspend fun updateConversationWorkspaceCwd(conversationId: Uuid, workspaceCwd: String?) {
        conversationDAO.updateWorkspaceCwd(conversationId.toString(), workspaceCwd.orEmpty())
    }

    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        require(conversationDAO.moveToAssistant(conversationId.toString(), assistantId.toString()) == 1) {
            "Conversation not found"
        }
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            nodes
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val conversationNumber: Long = 0,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
