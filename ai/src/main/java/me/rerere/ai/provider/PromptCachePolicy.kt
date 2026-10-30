package me.rerere.ai.provider

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

@Serializable
enum class PromptCacheKeyMode { AUTO, ENABLED, DISABLED }

@Serializable
enum class SessionAffinityMode { OFF, OPENAI, OPENROUTER }

/** Length-prefixed identity components avoid collisions without exposing local IDs. */
fun conversationPromptCacheKey(assistantId: String, conversationId: String): String {
    val identity = "heimao:prompt-cache:v1:" +
        "${assistantId.length}:$assistantId${conversationId.length}:$conversationId"
    return "hm-" + MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8)).promptCacheHex().take(32)
}

internal fun ByteArray.promptCacheHex(): String {
    val digits = "0123456789abcdef"
    val chars = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = digits[value ushr 4]
        chars[index * 2 + 1] = digits[value and 0x0f]
    }
    return String(chars)
}

internal fun ProviderSetting.OpenAI.usesPromptCacheKey(model: Model): Boolean =
    model.promptCacheKeyEnabled ?: when (promptCacheKeyMode) {
        PromptCacheKeyMode.AUTO -> baseUrl.toHttpUrlOrNull()?.let {
            it.isHttps && it.host == "api.openai.com"
        } == true
        PromptCacheKeyMode.ENABLED -> true
        PromptCacheKeyMode.DISABLED -> false
    }

/** Caller-owned fields, including explicit null/empty opt-outs, always win. */
internal fun JsonObject.withPromptCacheKey(provider: ProviderSetting.OpenAI, params: TextGenerationParams): JsonObject {
    if (!provider.usesPromptCacheKey(params.model) || containsKey("prompt_cache_key") || containsKey("promptCacheKey")) return this
    val key = params.promptCacheKey?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return this
    return JsonObject(this + ("prompt_cache_key" to JsonPrimitive(key)))
}

/** This is an opt-in proxy convention, not an upstream cache-hit guarantee. */
internal fun Request.Builder.withSessionAffinity(provider: ProviderSetting, params: TextGenerationParams): Request.Builder {
    val (baseUrl, mode) = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl to provider.sessionAffinity
        is ProviderSetting.Claude -> provider.baseUrl to provider.sessionAffinity
        is ProviderSetting.Google -> return this
    }
    if (mode == SessionAffinityMode.OFF) return this
    val key = params.promptCacheKey?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return this
    val host = baseUrl.toHttpUrlOrNull()?.host ?: return this
    if (host == "api.openai.com" || host == "api.anthropic.com") return this
    val names = when (mode) {
        SessionAffinityMode.OFF -> emptyList()
        SessionAffinityMode.OPENAI -> listOf("session_id", "x-client-request-id", "x-session-affinity")
        SessionAffinityMode.OPENROUTER -> listOf("x-session-id")
    }
    val existingHeaders = build().headers
    names.filter { existingHeaders[it] == null }.forEach { header(it, key) }
    return this
}
