package me.rerere.ai.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Provider-independent cache accounting. The legacy [cachedTokens] field remains the
 * read-count compatibility field used by existing UI and persisted messages.
 */
@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    val cacheReadTokens: Int = cachedTokens,
    val cacheWriteTokens: Int = 0,
    val provider: String? = null,
    val model: String? = null,
    /** Null for legacy data; false when usage omitted cache counters. */
    val cacheUsageReported: Boolean? = null,
)

/** Normalized usage shape used by diagnostics before it is projected to [TokenUsage]. */
@Serializable
data class CacheUsage(
    val totalInputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val provider: String,
    val model: String,
    val raw: JsonObject? = null,
)

object UsageNormalizer {
    fun normalize(
        totalInputTokens: Int?,
        completionTokens: Int? = null,
        totalTokens: Int? = null,
        cacheReadTokens: Int? = null,
        cacheWriteTokens: Int? = null,
        provider: String? = null,
        model: String? = null,
    ): TokenUsage {
        val input = totalInputTokens ?: 0
        val completion = completionTokens ?: 0
        val read = cacheReadTokens ?: 0
        val write = cacheWriteTokens ?: 0
        return TokenUsage(
            promptTokens = input,
            completionTokens = completion,
            cachedTokens = read,
            totalTokens = totalTokens ?: (input + completion),
            cacheReadTokens = read,
            cacheWriteTokens = write,
            provider = provider,
            model = model,
            cacheUsageReported = cacheReadTokens != null || cacheWriteTokens != null,
        )
    }

    fun toCacheUsage(
        usage: TokenUsage?,
        provider: String,
        model: String,
        raw: JsonObject? = null,
    ): CacheUsage = CacheUsage(
        totalInputTokens = usage?.promptTokens,
        cacheReadTokens = usage?.cacheReadTokens ?: usage?.cachedTokens,
        cacheWriteTokens = usage?.cacheWriteTokens,
        provider = provider,
        model = model,
        raw = raw,
    )
}

fun mergeCacheUsageReported(first: Boolean?, second: Boolean?): Boolean? = when {
    first == true || second == true -> true
    first == false || second == false -> false
    else -> null
}

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val hasOtherCacheRead = other.cacheReadTokens > 0 || other.cachedTokens > 0
    val cacheReadTokens = if (hasOtherCacheRead) {
        other.cacheReadTokens
    } else {
        this?.cacheReadTokens ?: this?.cachedTokens ?: 0
    }
    // Keep the legacy field as a read-count alias even when a caller only populated the
    // normalized cacheReadTokens field.
    val cachedTokens = if (hasOtherCacheRead) {
        if (other.cachedTokens > 0) other.cachedTokens else other.cacheReadTokens
    } else {
        this?.cachedTokens ?: cacheReadTokens
    }
    val cacheWriteTokens = if (other.cacheWriteTokens > 0) {
        other.cacheWriteTokens
    } else {
        this?.cacheWriteTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        provider = other.provider ?: this?.provider,
        model = other.model ?: this?.model,
        cacheUsageReported = mergeCacheUsageReported(this?.cacheUsageReported, other.cacheUsageReported),
    )
}
