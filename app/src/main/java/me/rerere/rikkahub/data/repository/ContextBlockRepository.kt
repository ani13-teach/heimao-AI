package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ContextBlockEntity
import me.rerere.rikkahub.data.model.ContextBlock
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class ContextBlockRepository(private val database: AppDatabase) {
    suspend fun getBlocks(conversationId: Uuid): List<ContextBlock> =
        database.contextBlockDao().getBlocks(conversationId.toString()).map { entity ->
            ContextBlock(
                id = Uuid.parse(entity.id),
                conversationId = Uuid.parse(entity.conversationId),
                assistantId = Uuid.parse(entity.assistantId),
                sources = JsonInstant.decodeFromString(entity.sources),
                sourceHash = entity.sourceHash, prefixHash = entity.prefixHash,
                summary = entity.summary, createdAt = entity.createdAt,
                formatVersion = entity.formatVersion,
            )
        }

    suspend fun insertBlocks(blocks: List<ContextBlock>) {
        if (blocks.isEmpty()) return
        database.withTransaction {
            blocks.groupBy { it.conversationId }.forEach { (conversationId, ownedBlocks) ->
                val owner = database.conversationDao().getConversationById(conversationId.toString())
                    ?: error("Context block conversation does not exist")
                require(ownedBlocks.all { it.assistantId.toString() == owner.assistantId }) {
                    "Context block assistant does not own conversation"
                }
            }
            database.contextBlockDao().insertBlocks(blocks.map { block ->
                ContextBlockEntity(
                    id = block.id.toString(), conversationId = block.conversationId.toString(),
                    assistantId = block.assistantId.toString(),
                    sources = JsonInstant.encodeToString(block.sources),
                    sourceHash = block.sourceHash, prefixHash = block.prefixHash,
                    summary = block.summary, createdAt = block.createdAt,
                    formatVersion = block.formatVersion,
                )
            })
        }
    }
}
