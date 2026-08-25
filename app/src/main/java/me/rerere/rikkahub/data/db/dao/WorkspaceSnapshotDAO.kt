package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkspaceSnapshotEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceSnapshotStatus

@Dao
interface WorkspaceSnapshotDAO {
    @Query(
        """
        SELECT * FROM workspace_snapshots
        WHERE workspace_id = :workspaceId AND status = 'READY'
        ORDER BY created_at DESC
        """
    )
    fun listReadyFlow(workspaceId: String): Flow<List<WorkspaceSnapshotEntity>>

    @Query(
        """
        SELECT * FROM workspace_snapshots
        WHERE workspace_id = :workspaceId AND status = 'READY'
        ORDER BY created_at DESC
        """
    )
    suspend fun listReady(workspaceId: String): List<WorkspaceSnapshotEntity>

    @Query("SELECT * FROM workspace_snapshots WHERE id = :id")
    suspend fun getById(id: String): WorkspaceSnapshotEntity?

    @Query(
        """
        SELECT * FROM workspace_snapshots
        WHERE status IN ('CREATING', 'RESTORING')
        ORDER BY created_at ASC
        """
    )
    suspend fun listInProgress(): List<WorkspaceSnapshotEntity>

    @Query(
        """
        SELECT COUNT(*) FROM workspace_snapshots
        WHERE workspace_id = :workspaceId AND status = 'READY'
        """
    )
    suspend fun countReady(workspaceId: String): Int

    @Query("SELECT COUNT(*) FROM workspace_snapshots WHERE status = 'READY'")
    suspend fun countAllReady(): Int

    @Query(
        """
        SELECT COALESCE(SUM(size), 0) FROM workspace_snapshots
        WHERE status = 'READY'
        """
    )
    suspend fun totalReadySize(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: WorkspaceSnapshotEntity)

    @Query(
        """
        UPDATE workspace_snapshots
        SET manifest_hash = :manifestHash, size = :size, status = :status
        WHERE id = :id
        """
    )
    suspend fun finalize(
        id: String,
        manifestHash: String,
        size: Long,
        status: String = WorkspaceSnapshotStatus.READY.name,
    ): Int

    @Query("UPDATE workspace_snapshots SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String): Int

    @Query("DELETE FROM workspace_snapshots WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM workspace_snapshots WHERE workspace_id = :workspaceId")
    suspend fun deleteForWorkspace(workspaceId: String): Int
}
