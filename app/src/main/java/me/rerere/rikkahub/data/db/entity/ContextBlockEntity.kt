package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "context_block",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"], childColumns = ["conversation_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversation_id")],
)
data class ContextBlockEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("assistant_id") val assistantId: String,
    val sources: String,
    @ColumnInfo("source_hash") val sourceHash: String,
    @ColumnInfo("prefix_hash") val prefixHash: String,
    val summary: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("format_version") val formatVersion: Int,
)
