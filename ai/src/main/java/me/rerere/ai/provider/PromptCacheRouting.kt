package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.util.PromptCacheKeyRejectedException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 是否允许把 `prompt_cache_key` 发给某个上游。
 *
 * 这个字段只是告诉服务端"这几轮属于同一个对话"，让请求排到同一台机器上，复用已经算过的开头。
 * 它不是所有网关都认的标准字段，不少 OpenAI 兼容上游会因为不认识的字段直接返回 400，
 * 所以默认的 [AUTO] 只对确认支持的上游发送，其余一律不发。
 */
@Serializable
enum class PromptCacheRouting {
    @SerialName("auto")
    AUTO,

    @SerialName("enabled")
    ENABLED,

    @SerialName("disabled")
    DISABLED,
}

/**
 * 确认接受 `prompt_cache_key` 的官方端点。
 *
 * 名单只放实测确认过的地址。开放平台、中转站和自建网关一律不算，
 * 需要用户自己确认后把供应商改成 [PromptCacheRouting.ENABLED]。
 */
private val KNOWN_PROMPT_CACHE_HOSTS = setOf("api.openai.com")

/**
 * 进程内记录：哪些供应商的上游已经明确说过“不认识 prompt_cache_key”。
 *
 * 记下来之后同一进程内不再给这家发这个字段，避免每次都先失败一次。
 */
object PromptCacheKeyRejection {
    private val rejected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 第一次记录某个供应商时回调一次，应用层用它把设置里的开关改成“关闭”。 */
    @Volatile
    var onRejected: ((providerId: String) -> Unit)? = null

    fun isRejected(providerId: String): Boolean = providerId in rejected

    fun markRejected(providerId: String) {
        if (rejected.add(providerId)) onRejected?.invoke(providerId)
    }

    /** 用户在界面上重新选择时清掉记录，给这家上游再来一次的机会。 */
    fun clear(providerId: String) {
        rejected.remove(providerId)
    }
}

/**
 * 算出这次请求实际要带的缓存键，返回 null 表示这次不带 `prompt_cache_key`。
 *
 * [key] 只接受调用方给的稳定会话键，这里不会自己造键：随机或每轮变化的键会让请求落到
 * 不同缓存桶，反而降低命中率。
 */
fun resolvePromptCacheKey(
    setting: ProviderSetting.OpenAI,
    globalEnabled: Boolean,
    key: String?,
): String? {
    if (!globalEnabled) return null
    if (PromptCacheKeyRejection.isRejected(setting.id.toString())) return null
    val stableKey = key?.trim().orEmpty()
    if (stableKey.isEmpty()) return null

    return when (setting.promptCacheRouting) {
        PromptCacheRouting.DISABLED -> null
        PromptCacheRouting.ENABLED -> stableKey
        PromptCacheRouting.AUTO -> {
            val host = setting.baseUrl.toHttpUrlOrNull()?.host
            stableKey.takeIf { host != null && host in KNOWN_PROMPT_CACHE_HOSTS }
        }
    }
}

/**
 * 上游说“不认识这个字段”的常见措辞。
 *
 * 不能只看到字段名就算数：有些网关会把整个请求体回显在报错里，那里面也有这个字段名。
 * 所以要求拒绝的措辞和字段名挨得很近（中间最多隔三个单词或三个非字母数字字符）。
 */
private val PROMPT_CACHE_REJECTION_PATTERNS = listOf(
    // Unknown parameter: prompt_cache_key / Unrecognized request argument supplied: prompt_cache_key
    Regex(
        "(?i)\\b(unknown|unrecognized|unsupported|unexpected|extra|additional|invalid)\\b" +
            "(?:\\s+\\w+){0,3}\\W{0,3}prompt_cache_key"
    ),
    // prompt_cache_key is not supported / prompt_cache_key: unsupported
    Regex(
        "(?i)prompt_cache_key\\W{0,2}(?:is\\s+|are\\s+)?(not supported|unsupported|unknown|" +
            "unrecognized|unexpected|not allowed|forbidden)"
    ),
)

/**
 * 上游是不是因为不认识的 `prompt_cache_key` 拒绝了这次请求。
 *
 * 三个条件必须同时成立，否则宁可当成普通错误上报：请求里确实带了这个字段、
 * 状态码是 400 或 422、响应正文里是这个字段被点名拒绝。
 */
fun isPromptCacheKeyRejected(
    sentBody: JsonObject,
    status: Int?,
    responseBody: String?,
): Boolean {
    if (status != 400 && status != 422) return false
    if (!sentBody.containsKey("prompt_cache_key")) return false
    val body = responseBody ?: return false
    return PROMPT_CACHE_REJECTION_PATTERNS.any { it.containsMatchIn(body) }
}

/**
 * 上游拒绝这个字段时返回要抛出的异常并记下这家供应商，其余情况返回 null。
 *
 * 记下来是为了让同一进程内的下一次请求直接跳过这个字段（外层重试会重新构造请求，
 * 那时就查到这个记录），同时通过回调把设置里的开关落成“关闭”。
 */
fun promptCacheKeyFailure(
    setting: ProviderSetting.OpenAI,
    sentBody: JsonObject,
    status: Int?,
    responseBody: String?,
): PromptCacheKeyRejectedException? {
    if (!isPromptCacheKeyRejected(sentBody, status, responseBody)) return null
    PromptCacheKeyRejection.markRejected(setting.id.toString())
    return PromptCacheKeyRejectedException(
        message = "Upstream rejected prompt_cache_key: $status $responseBody",
        status = status,
    )
}
