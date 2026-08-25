package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkspaceAuditEvent

@Dao
interface WorkspaceAuditDAO {
    @Insert
    suspend fun insert(event: WorkspaceAuditEvent): Long

    @Query(
        """
        SELECT * FROM workspace_audit_events
        WHERE workspace_id = :workspaceId
        ORDER BY started_at DESC, id DESC
        LIMIT :limit
        """
    )
    fun getRecentFlow(workspaceId: String, limit: Int): Flow<List<WorkspaceAuditEvent>>

    @Query(
        """
        SELECT * FROM workspace_audit_events
        WHERE workspace_id = :workspaceId
        ORDER BY started_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getRecent(workspaceId: String, limit: Int): List<WorkspaceAuditEvent>

    @Query("DELETE FROM workspace_audit_events WHERE workspace_id = :workspaceId")
    suspend fun deleteForWorkspace(workspaceId: String): Int
}
