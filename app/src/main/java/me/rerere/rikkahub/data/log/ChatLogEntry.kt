package me.rerere.rikkahub.data.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 日志等级。既是开关也是等级：`OFF` 就是关。
 */
@Serializable
enum class ChatLogLevel {
    OFF,
    ERRORS_ONLY,
    ALL;

    fun allows(entry: ChatLogEntry): Boolean = when (this) {
        OFF -> false
        ERRORS_ONLY -> entry.isError
        ALL -> true
    }
}

/**
 * 一条日志。刻意不存消息正文和工具参数原文，只存结构化字段。
 */
@Serializable
sealed interface ChatLogEntry {
    val timestamp: Long

    /** 错误类日志在「只记错误」等级下也会被记录。 */
    val isError: Boolean get() = this is ToolFailure || this is Crash

    /**
     * 一次模型请求的 token 明细。缓存命中率不落盘，由 [cacheHitRate] 从下面的计数算出来，
     * 免得两个字段对不上。
     */
    @Serializable
    @SerialName("request_usage")
    data class RequestUsage(
        override val timestamp: Long,
        val modelId: String,
        val providerId: String,
        val providerName: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val cacheReadTokens: Int,
        val cacheWriteTokens: Int,
        val totalTokens: Int,
    ) : ChatLogEntry {
        /** 缓存命中率 = 缓存读 ÷ 输入 token。没有输入时无意义，返回 null。 */
        val cacheHitRate: Double?
            get() = if (promptTokens > 0) cacheReadTokens.toDouble() / promptTokens else null
    }

    /** 一次工具调用失败。只记失败，成功不记。 */
    @Serializable
    @SerialName("tool_failure")
    data class ToolFailure(
        override val timestamp: Long,
        val toolName: String,
        val errorType: String,
        val errorMessage: String,
        /** 上游返回的报错码，拿不到时为 null。 */
        val upstreamCode: String? = null,
    ) : ChatLogEntry

    /** 崩溃。由 CrashHandler 在崩溃线程上同步写入。 */
    @Serializable
    @SerialName("crash")
    data class Crash(
        override val timestamp: Long,
        val threadName: String,
        val errorType: String,
        val stackTrace: String,
    ) : ChatLogEntry
}
