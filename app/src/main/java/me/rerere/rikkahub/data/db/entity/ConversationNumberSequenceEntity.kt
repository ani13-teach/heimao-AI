package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_number_sequence")
data class ConversationNumberSequenceEntity(
    @PrimaryKey
    val id: Int = 0,
    @ColumnInfo(name = "next_number")
    val nextNumber: Long = 1,
)
