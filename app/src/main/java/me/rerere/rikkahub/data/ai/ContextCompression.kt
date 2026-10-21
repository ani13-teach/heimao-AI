package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ContextCompression
import me.rerere.ai.ui.CODEMODE_METADATA_KEY
import me.rerere.ai.ui.withoutCodemodeTrace
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant as json
import me.rerere.rikkahub.data.datastore.validateContextCompressionSettings
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.findModelById
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 已明确指定的压缩模型失效时应报错，不能悄悄换成聊天模型。 */
internal fun Settings.compressionModelFor(chatModel: Model): Model =
    findModelById(compressModelId)
        ?: if (compressModelId == DEFAULT_AUTO_MODEL_ID) chatModel else {
            error("指定的压缩模型不可用，请在设置中的压缩模型选项重新选择")
        }

/** Deterministic local estimate, NOT a provider's exact tokenizer. Non-ASCII UTF-8 bytes
 * each reserve one token; contiguous ASCII runs reserve ceil(length / 4). */
internal fun estimateTextTokens(text: String): Int = textTokens(text).clampTokens()

private fun Long.clampTokens(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private fun textTokens(text: String): Long {
    var total = 0L
    var ascii = 0L
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        if (codePoint < 128) {
            ascii++
        } else {
            total += (ascii + 3L) / 4L
            ascii = 0L
            total += when {
                codePoint <= 0x7ff -> 2L
                codePoint in 0xd800..0xdfff -> 1L // JVM UTF-8 replacement for an unpaired surrogate
                codePoint <= 0xffff -> 3L
                else -> 4L
            }
        }
        offset += Character.charCount(codePoint)
    }
    return total + (ascii + 3L) / 4L
}

private const val MESSAGE_OVERHEAD = 4L
private const val PART_OVERHEAD = 2L
private const val SUMMARY_PREFIX = "[历史对话资料摘要]\n"
private const val SUMMARY_SUFFIX = "\n[以上为历史资料]"

/** Counts the full logical request without usage totals or local compression metadata.
 * Media reserve image=1024, video=4096, audio=2048; attachment URLs are not text. */
@Suppress("DEPRECATION")
internal fun estimatePromptTokens(prompt: LogicalPrompt): Int {
    val ordinaryMemory = messageTokens(
        if (prompt.memorySnapshot.isNotEmpty()) prompt.memorySnapshot else prompt.memory
    ) + messageTokens(prompt.memoryDelta)
    val claudeMemory = prompt.claudeMemoryCacheBlocks?.sumOf {
        MESSAGE_OVERHEAD + PART_OVERHEAD + textTokens(MessageRole.SYSTEM.name) + textTokens(it.text)
    }?.plus(messageTokens(prompt.memoryDelta) + messageTokens(prompt.claudeMemoryDelta))
        ?: (ordinaryMemory + messageTokens(prompt.claudeMemoryDelta))
    val toolTokens = prompt.tools.sumOf { tool ->
        MESSAGE_OVERHEAD + textTokens(tool.name) + textTokens(tool.description) +
            (tool.parameters()?.let { textTokens(json.encodeToJsonElement(it).toString()) } ?: 0L)
    }
    return (toolTokens + messageTokens(prompt.system) + messageTokens(prompt.history) +
        messageTokens(prompt.transientContext) + maxOf(ordinaryMemory, claudeMemory) +
        (prompt.latestUser?.let(::messageTokens) ?: 0L) + messageTokens(prompt.currentToolLoop))
        .clampTokens()
}

private fun messageTokens(messages: List<UIMessage>): Long = messages.sumOf(::messageTokens)
private fun messageTokens(message: UIMessage): Long =
    MESSAGE_OVERHEAD + textTokens(message.role.name) + message.parts.sumOf(::partTokens)

@Suppress("DEPRECATION")
private fun partTokens(part: UIMessagePart): Long = PART_OVERHEAD + when (part) {
    is UIMessagePart.Text -> textTokens(part.text)
    is UIMessagePart.Reasoning -> textTokens(part.reasoning) + metadataTokens(part.metadata)
    is UIMessagePart.Image -> 1024L
    is UIMessagePart.Video -> 4096L
    is UIMessagePart.Audio -> 2048L
    is UIMessagePart.Document -> textTokens(part.fileName) + textTokens(part.mime)
    is UIMessagePart.Tool -> textTokens(part.toolCallId) + textTokens(part.toolName) +
        textTokens(part.input) + part.output.sumOf(::partTokens) + metadataTokens(part.metadata)
    is UIMessagePart.ServerTool -> textTokens(part.toolCallId) + textTokens(part.toolName) +
        textTokens(part.input?.toString().orEmpty()) + textTokens(part.output?.toString().orEmpty()) +
        textTokens(part.status.name) + metadataTokens(part.metadata)
    is UIMessagePart.ToolCall -> textTokens(part.toolCallId) + textTokens(part.toolName) +
        textTokens(part.arguments) + metadataTokens(part.metadata)
    is UIMessagePart.ToolResult -> textTokens(part.toolCallId) + textTokens(part.toolName) +
        textTokens(part.arguments.toString()) + textTokens(part.content.toString()) + metadataTokens(part.metadata)
    UIMessagePart.Search -> 4L
}

private fun metadataTokens(metadata: JsonObject?): Long {
    val providerFields = metadata?.filterKeys { it != "contextCompression" && it != CODEMODE_METADATA_KEY }.orEmpty()
    return if (providerFields.isEmpty()) 0L else textTokens(JsonObject(providerFields).toString())
}

/** A user-level neutral container, never promoted to a system instruction. */
internal fun contextCompressionMessage(summary: String): UIMessage = UIMessage.user(
    SUMMARY_PREFIX + summary + SUMMARY_SUFFIX
).copy(isSynthetic = true)

private fun List<UIMessage>.historyEnd(): Int =
    indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }.let { if (it < 0) size else it }

/** Reapply the newest valid summary while keeping every system and the current tool chain. */
internal fun List<UIMessage>.withStoredContextCompression(): List<UIMessage> {
    for (index in historyEnd() - 1 downTo 0) {
        val marker = this[index].contextCompression ?: continue
        if (marker.summary.isBlank() || marker.sourceHash != prefixHash(take(index + 1))) continue
        return buildList {
            // Keep system messages in their original relative order within the covered prefix.
            addAll(this@withStoredContextCompression.take(index + 1).filter { it.role == MessageRole.SYSTEM })
            add(contextCompressionMessage(marker.summary))
            addAll(this@withStoredContextCompression.drop(index + 1))
        }
    }
    return this
}

private fun canonicalJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonicalJson(it.value) })
    is JsonArray -> JsonArray(element.map(::canonicalJson))
    else -> element
}

private fun hashPart(part: UIMessagePart): JsonElement {
    val encoded = json.encodeToJsonElement(part.withoutCodemodeTrace()) as JsonObject
    return when (part) {
        // Reasoning completion timestamps are streaming bookkeeping too.
        is UIMessagePart.Reasoning -> JsonObject(encoded.filterKeys { it != "finishedAt" })
        is UIMessagePart.Tool -> JsonObject(encoded + ("output" to JsonArray(part.output.map(::hashPart))))
        else -> encoded
    }
}

/** Only original identity, role and parts are hashed: streaming bookkeeping is excluded.
 * Canonical JSON makes map ordering and storage round trips irrelevant. */
private fun prefixHash(messages: List<UIMessage>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
    messages.forEach { message ->
        field(message.id.toString())
        field(message.role.name)
        field(canonicalJson(JsonArray(message.parts.map(::hashPart))).toString())
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal data class ContextCompactionResult(
    val prompt: LogicalPrompt,
    val messages: List<UIMessage>,
    val compressed: Boolean,
)

internal suspend fun compactContextIfNeeded(
    prompt: LogicalPrompt,
    messages: List<UIMessage>,
    triggerTokens: Int,
    targetTokens: Int,
    maxSourceTokens: Int = 16_000,
    summarize: suspend (String, Int) -> String,
): ContextCompactionResult {
    validateContextCompressionSettings(triggerTokens, targetTokens)?.let { error(it) }
    if (triggerTokens == 0 || estimatePromptTokens(prompt) < triggerTokens) {
        return ContextCompactionResult(prompt, messages, false)
    }
    require(maxSourceTokens > 0) { "压缩来源分块上限必须大于 0" }
    val historyEnd = messages.historyEnd()
    check(prompt.history.isNotEmpty() && historyEnd > 0 &&
        messages.take(historyEnd).any { it.role != MessageRole.SYSTEM }) {
        "没有可压缩的历史消息，无法满足上下文预算"
    }
    // A misplaced historical SYSTEM is still mandatory; never pass it to the summarizer.
    val historySystems = prompt.history.filter { it.role == MessageRole.SYSTEM }
    val reservedPrompt = prompt.copy(history = emptyList()).let {
        if (historySystems.isEmpty()) it else it.copy(system = it.system + historySystems)
    }
    val wrappedEmpty = reservedPrompt.copy(history = listOf(contextCompressionMessage("")))
    val summaryBudget = targetTokens.toLong() - estimatePromptTokens(wrappedEmpty).toLong()
    check(summaryBudget > 0) { "系统、工具、记忆及当前用户和工具链等必留内容已超出压缩目标，请提高压缩目标" }
    var source = renderCompressionHistory(prompt.history.filter { it.role != MessageRole.SYSTEM }
        .map { it.withoutCodemodeTrace() })
    check(source.isNotBlank()) { "历史消息没有可总结的内容，无法压缩上下文" }
    repeat(4) {
        coroutineContext.ensureActive()
        val chunks = splitCompressionSource(source, maxSourceTokens)
        var remainingBudget = summaryBudget
        val summaries = ArrayList<String>(chunks.size)
        chunks.forEachIndexed { index, chunk ->
            coroutineContext.ensureActive()
            val budget = (remainingBudget / (chunks.size - index)).coerceAtLeast(1L).clampTokens()
            val summary = summarize(chunk, budget)
            coroutineContext.ensureActive()
            check(summary.isNotBlank()) { "压缩模型返回了空摘要，请重试或更换压缩模型" }
            summaries.add(summary)
            remainingBudget = (remainingBudget - textTokens(summary) - if (index < chunks.lastIndex) 1L else 0L)
                .coerceAtLeast(0L)
        }
        val summary = summaries.joinToString("\n")
        val compacted = reservedPrompt.copy(history = listOf(contextCompressionMessage(summary)))
        if (estimatePromptTokens(compacted) <= targetTokens) {
            coroutineContext.ensureActive()
            val markerIndex = historyEnd - 1
            val marker = ContextCompression(prefixHash(messages.take(historyEnd)), summary)
            val updated = messages.mapIndexed { index, message ->
                if (index == markerIndex) message.copy(contextCompression = marker) else message
            }
            return ContextCompactionResult(compacted, updated, true)
        }
        source = summary
    }
    error("压缩摘要经过 4 轮归并后仍超出总请求预算，请提高压缩目标或更换压缩模型")
}

/** Exact source preservation, including newlines and surrogate pairs. No per-message truncation. */
private fun splitCompressionSource(source: String, maxTokens: Int): List<String> {
    val chunks = ArrayList<String>()
    var start = 0
    var offset = 0
    var tokens = 0L
    var ascii = 0L
    while (offset < source.length) {
        val cp = source.codePointAt(offset)
        val width = Character.charCount(cp)
        val nextAscii = if (cp < 128) ascii + 1 else 0L
        val nextTokens = if (cp < 128) {
            tokens - (ascii + 3L) / 4L + (nextAscii + 3L) / 4L
        } else {
            tokens + textTokens(source.substring(offset, offset + width))
        }
        if (nextTokens > maxTokens && offset > start) {
            chunks.add(source.substring(start, offset))
            start = offset
            tokens = 0L
            ascii = 0L
            continue
        }
        check(nextTokens <= maxTokens) { "压缩来源分块上限太小，无法容纳一个完整字符" }
        tokens = nextTokens
        ascii = nextAscii
        offset += width
    }
    if (start < source.length) chunks.add(source.substring(start))
    return chunks
}

@Suppress("DEPRECATION")
private fun renderCompressionHistory(messages: List<UIMessage>): String = messages.joinToString("\n\n") {
    "[${it.role.name}]\n" + it.parts.mapNotNull(::renderCompressionPart).joinToString("\n")
}

@Suppress("DEPRECATION")
private fun renderCompressionPart(part: UIMessagePart): String? = when (part) {
    is UIMessagePart.Text -> part.text
    is UIMessagePart.Reasoning -> null
    is UIMessagePart.Document -> "[文档：${part.fileName}]"
    is UIMessagePart.Image -> "[图片：${attachmentName(part.url)}]"
    is UIMessagePart.Video -> "[视频：${attachmentName(part.url)}]"
    is UIMessagePart.Audio -> "[音频：${attachmentName(part.url)}]"
    is UIMessagePart.Tool -> "[工具 ${part.toolName} ${part.toolCallId}]\n输入：${part.input}\n输出：\n" +
        part.output.mapNotNull(::renderCompressionPart).joinToString("\n")
    is UIMessagePart.ServerTool -> "[服务端工具 ${part.toolName} ${part.toolCallId} ${part.status}]\n" +
        "输入：${part.input ?: ""}\n输出：${part.output ?: ""}"
    is UIMessagePart.ToolCall -> "[工具调用 ${part.toolName} ${part.toolCallId}]\n${part.arguments}"
    is UIMessagePart.ToolResult -> "[工具结果 ${part.toolName} ${part.toolCallId}]\n" +
        "输入：${part.arguments}\n输出：${part.content}"
    UIMessagePart.Search -> "[搜索]"
}

private fun attachmentName(url: String): String = if (url.startsWith("data:")) {
    "内嵌附件"
} else {
    url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { "附件" }
}
