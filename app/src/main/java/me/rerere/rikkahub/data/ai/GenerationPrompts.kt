package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.LayeredMemoryContext
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

internal fun buildMemoryDeltaPrompt(
    changed: List<AssistantMemory>,
    deletedIds: List<Int>,
) = buildString {
    appendLine()
    append("**Memory Updates**")
    appendLine()
    append("These updates override the stable memory snapshot. Deleted IDs must be ignored.")
    appendLine()
    val json = buildJsonObject {
        put("upsert", buildJsonArray {
            changed.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        })
        put("deleted", buildJsonArray {
            deletedIds.forEach { add(JsonPrimitive(it)) }
        })
    }
    append(JsonInstantPretty.encodeToString(json))
    appendLine()
}
internal fun buildLayeredMemoryPrompt(context: LayeredMemoryContext) =
    buildString {
        appendLine()
        append(
            "Below is a compact memory index plus the most recent ACTIVE records. " +
                "The full content of other records is NOT injected. " +
                "Use the memory_read tool to search and load full records on demand."
        )
        appendLine()
        append("Rules:")
        appendLine()
        append("- Always query memory_read first when the topic may involve stored facts, " +
            "user preferences, or parameters; never guess parameter details from memory.")
        appendLine()
        append("- If a record's confidence contains ⚠️/待确认 and its updated date is older than 7 days, " +
            "ask the user to confirm it first before relying on it.")
        appendLine()
        append("- 中文：回答前先用 memory_read 检索相关记忆；updated 超过 7 天且含 ⚠️/待确认 的记忆，" +
            "先向前向用户确认再用；参数细节禁止凭印象编造。")
        appendLine()
        append("- Merge similar records with memory_write (edit) and use memory_consolidate " +
            "for conflict detection, confidence upgrades and archiving.")
        appendLine()
        append("[Index]")
        appendLine()
        append(context.indexText)
        appendLine()
        append("[ACTIVE]")
        appendLine()
        if (context.activeMemories.isEmpty()) {
            append("(none)")
        } else {
            context.activeMemories.forEach { memory ->
                append("#${memory.id} (${memory.category}) ")
                append(memory.title.ifBlank { "Legacy #${memory.id}" })
                append(" — ")
                append(memory.content)
                appendLine()
            }
        }
    }
