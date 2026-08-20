package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.repository.LightConversationEntity

@Dao
interface ConversationDAO {
    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAllPaging(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, conversation_number as conversationNumber FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationSummariesOfAssistant(assistantId: String): Flow<List<LightConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, conversation_number as conversationNumber FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, conversation_number as conversationNumber FROM conversationentity WHERE assistant_id = :assistantId AND folder_id = '' ORDER BY is_pinned DESC, update_at DESC")
    fun getUnfiledConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, conversation_number as conversationNumber FROM conversationentity WHERE folder_id = :folderId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfFolderPaging(folderId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, conversation_number as conversationNumber FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversations(searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, conversation_number as conversationNumber FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsPaging(searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistant(assistantId: String, searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, conversation_number as conversationNumber FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistantPaging(assistantId: String, searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND id IN (:ids)")
    suspend fun getConversationsByIdsOfAssistant(assistantId: String, ids: List<String>): List<ConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, conversation_number as conversationNumber FROM conversationentity WHERE assistant_id = :assistantId AND id IN (:ids)")
    suspend fun getConversationSummariesByIdsOfAssistant(
        assistantId: String,
        ids: List<String>,
    ): List<LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND conversation_number IN (:numbers)")
    suspend fun getConversationsByNumbersOfAssistant(assistantId: String, numbers: List<Long>): List<ConversationEntity>

    @Query("SELECT id FROM conversationentity WHERE conversation_number = :number LIMIT 1")
    suspend fun getConversationIdByNumber(number: Long): String?

    @Query("INSERT OR IGNORE INTO conversation_number_sequence (id, next_number) SELECT 0, COALESCE(MAX(conversation_number), 0) + 1 FROM conversationentity")
    suspend fun ensureConversationNumberSequence()

    @Query("SELECT next_number FROM conversation_number_sequence WHERE id = 0")
    suspend fun getNextConversationNumber(): Long?

    @Query("UPDATE conversation_number_sequence SET next_number = next_number + 1 WHERE id = 0")
    suspend fun incrementConversationNumber()

    @Query("UPDATE conversation_number_sequence SET next_number = CASE WHEN next_number < :nextNumber THEN :nextNumber ELSE next_number END WHERE id = 0")
    suspend fun ensureNextConversationNumber(nextNumber: Long)

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    fun getConversationFlowById(id: String): Flow<ConversationEntity?>

    @Query("SELECT id FROM conversationentity")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET nodes = '[]' WHERE id = :id")
    suspend fun resetConversationNodes(id: String)

    @Query("DELETE FROM conversationentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversationentity")
    suspend fun deleteAll()

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, conversation_number as conversationNumber FROM conversationentity WHERE is_pinned = 1 ORDER BY update_at DESC")
    fun getPinnedConversations(): Flow<List<LightConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    @Query("UPDATE conversationentity SET folder_id = :folderId WHERE id = :id")
    suspend fun updateFolderId(id: String, folderId: String)

    @Query("UPDATE conversationentity SET folder_id = '' WHERE folder_id = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("SELECT COUNT(*) FROM conversationentity")
    suspend fun countAll(): Int

    @Query(
        "SELECT strftime('%Y-%m-%d', create_at/1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count " +
            "FROM conversationentity " +
            "WHERE create_at >= :startMillis " +
            "GROUP BY day"
    )
    suspend fun getConversationCountPerDay(startMillis: Long): List<ConversationDayCount>
}

data class ConversationDayCount(val day: String, val count: Int)
