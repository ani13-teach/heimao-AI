package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

internal fun buildMemoryBasePrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memory Base**")
        appendLine()
        appendLine("This is the complete baseline memory projection. Apply every later Memory Patch block in order.")
        appendLine("For the same ID, the later upsert wins; a deleted tombstone invalidates all older content for that ID.")
        val json = buildJsonArray {
            memories.sortedBy { it.id }.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

internal fun buildMemoryPatchPrompt(
    changed: List<AssistantMemory>,
    deletedIds: List<Int>,
) = buildString {
    appendLine()
    append("**Memory Patch**")
    appendLine()
    appendLine("Apply this patch after all preceding memory blocks. For the same ID, this later upsert wins.")
    appendLine("IDs in deleted are tombstones: ignore any older content for those IDs unless a later patch upserts them.")
    val json = buildJsonObject {
        put("upsert", buildJsonArray {
            changed.sortedBy { it.id }.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        })
        put("deleted", buildJsonArray {
            deletedIds.sorted().forEach { add(JsonPrimitive(it)) }
        })
    }
    append(JsonInstantPretty.encodeToString(json))
    appendLine()
}

internal fun buildMemoryDeltaPrompt(
    changed: List<AssistantMemory>,
    deletedIds: List<Int>,
) = buildMemoryPatchPrompt(changed, deletedIds)
