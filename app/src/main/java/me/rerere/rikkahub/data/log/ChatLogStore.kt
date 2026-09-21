package me.rerere.rikkahub.data.log

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 日志的唯一入口。负责等级门控和落盘时机，字段怎么填都在这里，调用方不用管。
 */
class ChatLogStore(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
) {
    companion object {
        /** 日志保留 24 小时，启动时清一次过期的。 */
        const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L

        /** 一次最多读这么多条，防止长期没重启后把列表撑爆。 */
        const val MAX_ENTRIES = 500
    }

    /** 记一次模型请求的 token 明细。provider 没报 usage 时按 0 记，方便看出「这家不报数」。 */
    fun recordTokenUsage(model: Model, provider: ProviderSetting, usage: TokenUsage?) {
        val resolved = usage ?: TokenUsage()
        record(
            ChatLogEntry.RequestUsage(
                timestamp = System.currentTimeMillis(),
                modelId = sanitizeLogText(model.modelId.ifBlank { model.id.toString() }),
                providerId = provider.id.toString(),
                providerName = sanitizeLogText(provider.name),
                promptTokens = resolved.promptTokens,
                completionTokens = resolved.completionTokens,
                cacheReadTokens = resolved.cacheReadTokens,
                cacheWriteTokens = resolved.cacheWriteTokens,
                totalTokens = resolved.totalTokens.takeIf { it > 0 }
                    ?: (resolved.promptTokens + resolved.completionTokens),
            )
        )
    }

    /** 记一次工具调用失败。成功不记。 */
    fun recordToolFailure(toolName: String, error: Throwable) {
        record(
            ChatLogEntry.ToolFailure(
                timestamp = System.currentTimeMillis(),
                toolName = sanitizeLogText(toolName),
                errorType = error.javaClass.name,
                errorMessage = sanitizeLogText(error.message ?: ""),
                upstreamCode = extractUpstreamCode(error),
            )
        )
    }

    /** 记一次记忆工具调用失败。成功不记。 */
    fun recordMemoryFailure(
        toolName: String,
        action: String?,
        recordId: Int?,
        error: Throwable,
    ) {
        record(
            ChatLogEntry.MemoryFailure(
                timestamp = System.currentTimeMillis(),
                toolName = sanitizeLogText(toolName),
                action = action?.let(::sanitizeLogText),
                recordId = recordId,
                errorType = error.javaClass.name,
                errorMessage = sanitizeLogText(error.message ?: ""),
            )
        )
    }

    suspend fun recent(limit: Int = MAX_ENTRIES): List<ChatLogEntry> = withContext(Dispatchers.IO) {
        ChatLogFile.read(context).sortedByDescending { it.timestamp }.take(limit)
    }

    /** 启动时调用：丢掉早于保留期的记录，顺手压实读不出来的坏行。 */
    suspend fun pruneExpired(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val kept = ChatLogFile.read(context)
            .filter { now - it.timestamp < RETENTION_MILLIS }
            .sortedBy { it.timestamp }
        ChatLogFile.writeAll(context, kept)
    }

    suspend fun clear() = withContext(Dispatchers.IO) { ChatLogFile.delete(context) }

    private fun record(entry: ChatLogEntry) {
        if (!settingsStore.settingsFlow.value.chatLogLevel.allows(entry)) return
        // 落盘放 IO 线程，且不等结果：写日志不能拖慢生成，也不能让写失败冒到调用方。
        appScope.launch(Dispatchers.IO) { ChatLogFile.append(context, entry) }
    }
}
