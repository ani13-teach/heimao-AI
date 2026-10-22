package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.rerere.rikkahub.data.db.entity.ContextBlockEntity

@Dao
interface ContextBlockDAO {
    @Query("SELECT * FROM context_block WHERE conversation_id = :conversationId ORDER BY created_at, id")
    suspend fun getBlocks(conversationId: String): List<ContextBlockEntity>

    @Transaction
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBlocks(blocks: List<ContextBlockEntity>)
}
