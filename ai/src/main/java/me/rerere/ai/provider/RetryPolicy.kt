package me.rerere.ai.provider

import java.io.IOException
import kotlinx.coroutines.CancellationException
import me.rerere.ai.util.HttpException

/**
 * 用户可配置的自动重试参数。
 *
 * 只回答两个问题：还能不能再试一次，以及要等多久。网络、界面和 Android 都不在这里。
 * 传 null（不使用策略）表示一次请求只尝试一次，与加入重试之前的行为一致。
 */
data class RetryPolicy(
    /** 两次尝试之间的等待时间。 */
    val intervalMillis: Long,
    /** 从首次失败算起，允许重试的总时长上限。 */
    val maxDurationMillis: Long,
    /** 固定尝试上限，避免间隔很小时对不可用端点持续发请求。 */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 10

        /** 间隔下限，防止配置成 0 变成忙循环。 */
        const val MIN_INTERVAL_MILLIS = 1L
    }

    /**
     * 第 [failedAttempt] 次尝试失败后应等待的毫秒数。
     *
     * 返回 null 表示这次失败之后不再重试：不值得重试、次数用尽、或等待会超过总时长上限。
     * 「已用时长 + 间隔恰好等于上限」仍允许再试一次，只有真正超出才停止。
     */
    fun delayBeforeRetry(
        failedAttempt: Int,
        elapsedMillis: Long,
        error: Throwable,
    ): Long? {
        if (!isRetryableFailure(error)) return null
        if (failedAttempt >= maxAttempts.coerceAtLeast(1)) return null
        val delay = intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS)
        val elapsed = elapsedMillis.coerceAtLeast(0L)
        if (elapsed + delay > maxDurationMillis.coerceAtLeast(0L)) return null
        return delay
    }
}

private val RETRYABLE_STATUS_CODES = setOf(408, 409, 425, 429)

/**
 * 这次失败是否值得重试。
 *
 * 传输层失败（连接中断、超时、流提前结束）与限流、服务端错误可以重试；
 * 配置类错误（无效密钥、请求格式错误）立即上报，不消耗重试预算。
 * 取消永远不重试。
 */
fun isRetryableFailure(error: Throwable): Boolean = when (error) {
    is CancellationException -> false
    is IOException -> true
    is HttpException -> error.status.isRetryableStatus()
    else -> false
}

/**
 * 状态码未知时按可重试处理：流内错误事件发生在 HTTP 200 之后，
 * 常见原因是服务端过载或限流，状态码拿不到不代表失败不可恢复。
 */
private fun Int?.isRetryableStatus(): Boolean =
    this == null || this in RETRYABLE_STATUS_CODES || this in 500..599
