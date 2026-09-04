package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo

/** Small projection used by the portable workspace manifest; it never loads message nodes. */
data class ConversationWorkspaceReference(
    @ColumnInfo(name = "id") val conversationId: String,
    @ColumnInfo(name = "assistant_id") val assistantId: String,
    val workspaceId: String?,
)
