package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo(name = "category", defaultValue = "GENERAL")
    val category: String = "GENERAL",
    @ColumnInfo(name = "title", defaultValue = "")
    val title: String = "",
    @ColumnInfo(name = "updated", defaultValue = "1970-01-01")
    val updated: String = "1970-01-01",
    @ColumnInfo(name = "confidence_flags", defaultValue = "[]")
    val confidenceFlags: String = "[]",
    @ColumnInfo(name = "tags", defaultValue = "[]")
    val tags: String = "[]",
    @ColumnInfo(name = "product", defaultValue = "")
    val product: String = "",
    @ColumnInfo(name = "status", defaultValue = "active")
    val status: String = "active",
)
