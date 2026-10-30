package me.rerere.rikkahub.data.log

/** Aggregates only reported cache samples; absent counters never become measured misses. */
data class CacheLogSummary(
    val sessionKey: String?,
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val steps: Int,
    val usageReports: Int,
    val cacheReports: Int,
    val hitSteps: Int,
    val inputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
) {
    val cacheHitRate: Double?
        get() = if (inputTokens > 0) cacheReadTokens.toDouble() / inputTokens else null
}

fun summarizeCacheLogs(entries: List<ChatLogEntry>): List<CacheLogSummary> = entries
    .filterIsInstance<ChatLogEntry.RequestUsage>()
    .groupBy { Triple(it.sessionKey, it.providerId, it.modelId) }
    .map { (identity, requests) ->
        val samples = requests.filter { it.usageReported == true && it.cacheUsageReported == true }
        CacheLogSummary(
            sessionKey = identity.first,
            providerId = identity.second,
            providerName = requests.maxBy { it.timestamp }.providerName,
            modelId = identity.third,
            steps = requests.size,
            usageReports = requests.count { it.usageReported == true },
            cacheReports = samples.size,
            hitSteps = samples.count { it.cacheReadTokens > 0 },
            inputTokens = samples.sumOf { it.promptTokens.toLong() },
            cacheReadTokens = samples.sumOf { it.cacheReadTokens.toLong() },
            cacheWriteTokens = samples.sumOf { it.cacheWriteTokens.toLong() },
        )
    }
