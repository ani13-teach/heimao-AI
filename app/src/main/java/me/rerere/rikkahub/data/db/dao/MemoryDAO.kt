package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY id DESC")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY id DESC")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY id DESC LIMIT :limit")
    suspend fun getMemoriesOfAssistantForPrompt(assistantId: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity ORDER BY id DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity ORDER BY id DESC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND id = :id AND status = :status")
    suspend fun getMemoryInScope(assistantId: String, id: Int, status: String): MemoryEntity?

    @Query(
        """
        SELECT * FROM memoryentity
        WHERE assistant_id = :assistantId AND status = 'active'
        ORDER BY updated DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getMemoriesForLayeredIndex(assistantId: String, limit: Int): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memoryentity
        WHERE assistant_id = :assistantId AND status = 'active' AND category = 'ACTIVE'
        ORDER BY updated DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getActiveMemoriesForPrompt(assistantId: String, limit: Int): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memoryentity
        WHERE assistant_id = :assistantId AND status = :status
          AND (:category IS NULL OR category = :category)
          AND (:confidencePattern IS NULL OR confidence_flags LIKE :confidencePattern ESCAPE '\')
          AND (:keyword = '' OR title LIKE '%' || :keyword || '%' ESCAPE '\'
               OR tags LIKE '%' || :keyword || '%' ESCAPE '\'
               OR content LIKE '%' || :keyword || '%' ESCAPE '\')
        ORDER BY updated DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun searchMemories(
        assistantId: String,
        keyword: String,
        category: String?,
        confidencePattern: String?,
        status: String,
        limit: Int,
    ): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memoryentity
        WHERE assistant_id = :assistantId AND status = :status
          AND (:category IS NULL OR category = :category)
          AND (:confidencePattern IS NULL OR confidence_flags LIKE :confidencePattern ESCAPE '\')
          AND (:keyword = '' OR title LIKE '%' || :keyword || '%' ESCAPE '\'
               OR tags LIKE '%' || :keyword || '%' ESCAPE '\'
               OR content LIKE '%' || :keyword || '%' ESCAPE '\')
          AND (:afterUpdated IS NULL OR updated < :afterUpdated
               OR (updated = :afterUpdated AND id < :afterId))
        ORDER BY updated DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun searchMemoriesPage(
        assistantId: String,
        keyword: String,
        category: String?,
        confidencePattern: String?,
        status: String,
        limit: Int,
        afterUpdated: String?,
        afterId: Int?,
    ): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memoryentity
        WHERE assistant_id = :assistantId AND status = :status
        ORDER BY id DESC
        """
    )
    suspend fun getMemoriesByStatus(assistantId: String, status: String): List<MemoryEntity>

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
