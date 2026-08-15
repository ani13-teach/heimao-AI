package me.rerere.rikkahub.data.summary

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import kotlin.uuid.Uuid

private val sourceDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

/** A compact, visible-content-only representation used by the summary pipeline. */
data class ConversationSummarySource(
    val number: Long,
    val id: Uuid,
    val title: String,
    val createdAt: String,
    val messages: List<ConversationSummaryMessage>,
)

data class ConversationSummaryMessage(
    val role: MessageRole,
    val text: String,
)

fun Conversation.toSummarySource(): ConversationSummarySource {
    val createdAtText = createAt.atZone(ZoneId.systemDefault()).toLocalDateTime().format(sourceDateFormatter)
    return ConversationSummarySource(
        number = conversationNumber,
        id = id,
        title = title,
        createdAt = createdAtText,
        messages = currentMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .mapNotNull { message ->
                val text = message.visibleSummaryText()
                text.takeIf { it.isNotBlank() }?.let {
                    ConversationSummaryMessage(message.role, it)
                }
            },
    )
}

fun UIMessage.visibleSummaryText(): String {
    return parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.takeIf { it.isNotBlank() }
            is UIMessagePart.Image -> "[image]"
            is UIMessagePart.Video -> "[video]"
            is UIMessagePart.Audio -> "[audio]"
            is UIMessagePart.Document -> "[document: ${part.fileName}]"
            // Reasoning and tool output are deliberately excluded from source summaries.
            else -> null
        }
    }.joinToString("\n")
}

fun buildConversationSummarySource(
    conversations: List<Conversation>,
    maxCharacters: Int = Int.MAX_VALUE,
): String {
    require(maxCharacters > 0) { "maxCharacters must be positive" }
    val result = StringBuilder()
    conversations.forEach { conversation ->
        val source = conversation.toSummarySource()
        val rendered = buildString {
            appendLine("=== conversation #${source.number} ===")
            appendLine("title: ${source.title.ifBlank { "(untitled)" }}")
            appendLine("created_at: ${source.createdAt}")
            source.messages.forEach { message ->
                appendLine("[${message.role.name}]")
                appendLine(message.text)
            }
            append("=== end conversation #${source.number} ===")
        }
        val separatorLength = if (result.isEmpty()) 0 else 2
        require(result.length + separatorLength + rendered.length <= maxCharacters) {
            "Conversation summary source exceeds the configured size limit"
        }
        if (result.isNotEmpty()) result.append("\n\n")
        result.append(rendered)
    }
    return result.toString()
}

/** Split only at line boundaries where possible, while guaranteeing progress for long lines. */
fun splitConversationSummarySource(source: String, maxCharacters: Int): List<String> {
    require(maxCharacters > 0) { "maxCharacters must be positive" }
    if (source.isEmpty()) return emptyList()
    if (source.length <= maxCharacters) return listOf(source)

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) {
            chunks += current.toString()
            current.clear()
        }
    }

    source.lineSequence().forEach { line ->
        val additionLength = line.length + if (current.isEmpty()) 0 else 1
        if (additionLength <= maxCharacters && current.length + additionLength <= maxCharacters) {
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
            return@forEach
        }

        flush()
        if (line.length <= maxCharacters) {
            current.append(line)
        } else {
            var start = 0
            while (start < line.length) {
                val end = (start + maxCharacters).coerceAtMost(line.length)
                chunks += line.substring(start, end)
                start = end
            }
        }
    }
    flush()
    return chunks
}

data class ConversationSelection(
    val numbers: Set<Long>,
    val ids: Set<Uuid>,
)

fun parseConversationSelection(
    input: String,
    maxRangeSize: Long = 500,
): Result<ConversationSelection> = runCatching {
    val tokens = input
        .replace('，', ',')
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    require(tokens.isNotEmpty()) { "Enter at least one conversation number or ID" }

    val numbers = mutableSetOf<Long>()
    val ids = mutableSetOf<Uuid>()
    tokens.forEach { token ->
        val id = runCatching { Uuid.parse(token) }.getOrNull()
        if (id != null) {
            ids += id
        } else {
            numbers += parseConversationNumberSelection(token, maxRangeSize).getOrThrow()
        }
    }
    ConversationSelection(numbers = numbers, ids = ids)
}

/** Parse `1,3,8-10` input for permanent conversation numbers. */
fun parseConversationNumberSelection(
    input: String,
    maxRangeSize: Long = 500,
): Result<Set<Long>> = runCatching {
    val tokens = input
        .replace('，', ',')
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    require(tokens.isNotEmpty()) { "Enter at least one conversation number" }

    buildSet {
        tokens.forEach { token ->
            val rangeParts = token.split('-', limit = 2).map { it.trim() }
            when (rangeParts.size) {
                1 -> add(rangeParts[0].toLong().also { require(it > 0) })
                2 -> {
                    val start = rangeParts[0].toLong()
                    val end = rangeParts[1].toLong()
                    require(start > 0 && end >= start) { "Invalid conversation number range" }
                    require(end - start + 1 <= maxRangeSize) { "Conversation number range is too large" }
                    addAll(start..end)
                }
                else -> error("Invalid conversation number: $token")
            }
        }
    }
}
