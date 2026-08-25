package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Application-side record of a workspace operation. Shell targets are always the constant
 * `shell`; command metadata is stored in allow-listed details and never as command text.
 */
@Entity(
    tableName = "workspace_audit_events",
    indices = [
        Index(value = ["workspace_id", "started_at"]),
        Index(value = ["conversation_id"]),
    ],
)
data class WorkspaceAuditEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("actor")
    val actor: String,
    @ColumnInfo("operation")
    val operation: String,
    /** A workspace path, or the constant `shell` for shell operations. */
    @ColumnInfo("target")
    val target: String,
    @ColumnInfo("cwd")
    val cwd: String = "",
    @ColumnInfo("started_at")
    val startedAt: Long,
    @ColumnInfo("finished_at")
    val finishedAt: Long,
    @ColumnInfo("result")
    val result: String,
    @ColumnInfo("exit_code")
    val exitCode: Int? = null,
    @ColumnInfo("timed_out")
    val timedOut: Boolean = false,
    @ColumnInfo("bytes")
    val bytes: Long? = null,
    /** Compact JSON metadata only; never operation input or output. */
    @ColumnInfo("details")
    val details: String = "{}",
)
