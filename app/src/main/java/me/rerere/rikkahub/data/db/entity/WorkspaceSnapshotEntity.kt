package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Why an application-side workspace snapshot was created. */
enum class WorkspaceSnapshotReason {
    MANUAL,
    PRE_RESTORE,
}

/** Lifecycle state of a snapshot file. Only READY snapshots are offered to the user. */
enum class WorkspaceSnapshotStatus {
    CREATING,
    READY,
    RESTORING,
    FAILED,
}

@Entity(
    tableName = "workspace_snapshots",
    indices = [
        Index(value = ["workspace_id", "created_at"]),
        Index(value = ["path"], unique = true),
    ],
)
data class WorkspaceSnapshotEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("workspace_id")
    val workspaceId: String,
    /** Absolute path under the app-private snapshots directory. */
    @ColumnInfo("path")
    val path: String,
    /** SHA-256 of the UTF-8 encoded snapshot manifest. */
    @ColumnInfo("manifest_hash")
    val manifestHash: String,
    @ColumnInfo("size")
    val size: Long,
    @ColumnInfo("reason")
    val reason: String,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
) {
    fun snapshotReason(): WorkspaceSnapshotReason? =
        runCatching { WorkspaceSnapshotReason.valueOf(reason) }.getOrNull()

    fun snapshotStatus(): WorkspaceSnapshotStatus? =
        runCatching { WorkspaceSnapshotStatus.valueOf(status) }.getOrNull()
}
