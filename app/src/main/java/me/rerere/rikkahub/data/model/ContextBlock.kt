package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ContextBlock(
    val id: Uuid = Uuid.random(),
    val conversationId: Uuid,
    val assistantId: Uuid,
    val sources: List<ContextBlockSource>,
    val sourceHash: String,
    val prefixHash: String,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis(),
    val formatVersion: Int = 1,
)

@Serializable
data class ContextBlockSource(val nodeId: Uuid, val messageId: Uuid)
