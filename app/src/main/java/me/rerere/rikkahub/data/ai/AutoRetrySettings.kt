package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.RetryPolicy
import me.rerere.rikkahub.data.datastore.Settings

/** 重试间隔的可选范围（秒）。界面滑条与收窄逻辑共用同一组边界。 */
const val AUTO_RETRY_MIN_INTERVAL_SECONDS = 1
const val AUTO_RETRY_MAX_INTERVAL_SECONDS = 60

/** 最大重试时间的可选范围（分钟）。 */
const val AUTO_RETRY_MIN_DURATION_MINUTES = 1
const val AUTO_RETRY_MAX_DURATION_MINUTES = 30

/** 最多尝试次数的可选范围（次，含首次尝试）。 */
const val AUTO_RETRY_MIN_ATTEMPTS = 2
const val AUTO_RETRY_MAX_ATTEMPTS = 20

/**
 * 把用户设置映射成请求层使用的重试策略。
 *
 * 关闭开关时返回 null，请求层就是一次请求只尝试一次。取值范围在这里收窄，
 * 历史数据或手工导入的越界值不会变成忙循环或超长等待。
 */
fun Settings.toRetryPolicy(): RetryPolicy? {
    if (!autoRetryEnabled) return null
    return RetryPolicy(
        intervalMillis = autoRetryIntervalSeconds
            .coerceIn(AUTO_RETRY_MIN_INTERVAL_SECONDS, AUTO_RETRY_MAX_INTERVAL_SECONDS)
            .toLong() * 1_000L,
        maxDurationMillis = autoRetryMaxDurationMinutes
            .coerceIn(AUTO_RETRY_MIN_DURATION_MINUTES, AUTO_RETRY_MAX_DURATION_MINUTES)
            .toLong() * 60_000L,
        maxAttempts = autoRetryMaxAttempts
            .coerceIn(AUTO_RETRY_MIN_ATTEMPTS, AUTO_RETRY_MAX_ATTEMPTS),
    )
}
