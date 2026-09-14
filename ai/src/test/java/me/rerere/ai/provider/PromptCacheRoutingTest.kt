package me.rerere.ai.provider

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.util.PromptCacheKeyRejectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheRoutingTest {
    private val key = "chat_" + "a".repeat(64)

    private fun openAi(
        baseUrl: String,
        routing: PromptCacheRouting = PromptCacheRouting.AUTO,
    ) = ProviderSetting.OpenAI(baseUrl = baseUrl, promptCacheRouting = routing)

    @Test
    fun `auto sends the session key only to confirmed upstreams`() {
        assertEquals(key, resolvePromptCacheKey(openAi("https://api.openai.com/v1"), true, key))
        assertNull(
            resolvePromptCacheKey(openAi("https://strict.example.com/v1"), true, key),
        )
        assertNull(
            resolvePromptCacheKey(openAi("https://api.deepseek.com/v1"), true, key),
        )
    }

    @Test
    fun `explicit override wins over auto detection`() {
        assertEquals(
            key,
            resolvePromptCacheKey(
                openAi("https://strict.example.com/v1", PromptCacheRouting.ENABLED),
                true,
                key,
            ),
        )
        assertNull(
            resolvePromptCacheKey(
                openAi("https://api.openai.com/v1", PromptCacheRouting.DISABLED),
                true,
                key,
            ),
        )
    }

    @Test
    fun `global switch and missing key both stop the field`() {
        val provider = openAi("https://api.openai.com/v1", PromptCacheRouting.ENABLED)
        assertNull(resolvePromptCacheKey(provider, false, key))
        assertNull(resolvePromptCacheKey(provider, true, null))
        assertNull(resolvePromptCacheKey(provider, true, "   "))
    }

    @Test
    fun `unparsable base url counts as unsupported in auto mode`() {
        assertNull(resolvePromptCacheKey(openAi("not-a-url"), true, key))
        // 用户明确开启时，地址能不能解析不改变决定
        assertEquals(
            key,
            resolvePromptCacheKey(openAi("not-a-url", PromptCacheRouting.ENABLED), true, key),
        )
    }

    @Test
    fun `rejected upstream is skipped until the user picks again`() {
        val provider = openAi("https://relay.example.com/v1", PromptCacheRouting.ENABLED)
        val providerId = provider.id.toString()

        assertEquals(key, resolvePromptCacheKey(provider, true, key))

        PromptCacheKeyRejection.markRejected(providerId)
        assertNull(resolvePromptCacheKey(provider, true, key))

        PromptCacheKeyRejection.clear(providerId)
        assertEquals(key, resolvePromptCacheKey(provider, true, key))
    }

    @Test
    fun `first rejection tells the app once so the setting can be written back`() {
        val provider = openAi("https://relay.example.com/v1", PromptCacheRouting.ENABLED)
        val providerId = provider.id.toString()
        val seen = mutableListOf<String>()
        PromptCacheKeyRejection.onRejected = { seen.add(it) }
        try {
            PromptCacheKeyRejection.markRejected(providerId)
            PromptCacheKeyRejection.markRejected(providerId)
        } finally {
            PromptCacheKeyRejection.onRejected = null
            PromptCacheKeyRejection.clear(providerId)
        }

        assertEquals(listOf(providerId), seen)
    }

    @Test
    fun `rejection detection needs a sent field a strict status and a naming body`() {
        val sent = buildJsonObject {
            put("model", "gpt-test")
            put("prompt_cache_key", key)
        }
        val withoutField = buildJsonObject { put("model", "gpt-test") }

        // 各家上游真实会拿出来的措辞
        assertTrue(
            isPromptCacheKeyRejected(
                sent,
                400,
                """{"error":{"message":"Unrecognized request argument supplied: prompt_cache_key"}}""",
            ),
        )
        assertTrue(isPromptCacheKeyRejected(sent, 400, "Unknown parameter: prompt_cache_key"))
        assertTrue(
            isPromptCacheKeyRejected(
                sent,
                422,
                """Invalid JSON payload received. Unknown name "prompt_cache_key": Cannot find field.""",
            ),
        )
        assertTrue(isPromptCacheKeyRejected(sent, 400, "prompt_cache_key is not supported here"))

        // 前置条件：状态码、真的带了字段、响应正文存在
        assertFalse(isPromptCacheKeyRejected(sent, 401, "Unknown parameter: prompt_cache_key"))
        assertFalse(isPromptCacheKeyRejected(sent, null, "Unknown parameter: prompt_cache_key"))
        assertFalse(isPromptCacheKeyRejected(withoutField, 400, "Unknown parameter: prompt_cache_key"))
        assertFalse(isPromptCacheKeyRejected(sent, 400, null))
    }

    @Test
    fun `detection does not fire when the body only mentions the field`() {
        val sent = buildJsonObject { put("prompt_cache_key", key) }

        // 真正的原因是别的，只是凑巧提到了这个字段
        assertFalse(
            isPromptCacheKeyRejected(
                sent,
                400,
                """{"error":{"message":"prompt_cache_key is valid; model_not_found"}}""",
            ),
        )
        // 网关把整个请求体回显回来：字段名和型号报错在同一个 JSON 里
        assertFalse(
            isPromptCacheKeyRejected(
                sent,
                400,
                """{"error":{"message":"model_not_found","type":"invalid_request_error"},""" +
                    """"request":{"prompt_cache_key":"$key"}}""",
            ),
        )
        assertFalse(
            isPromptCacheKeyRejected(
                sent,
                400,
                """{"choices":[],"usage":{"prompt_cache_key":"seen"}}""",
            ),
        )
    }

    @Test
    fun `rejection records the provider and keeps the upstream message`() {
        val provider = openAi("https://relay.example.com/v1", PromptCacheRouting.ENABLED)
        val sent = buildJsonObject { put("prompt_cache_key", key) }
        val rejectionBody = "Unsupported parameter: prompt_cache_key"

        val error = promptCacheKeyFailure(provider, sent, 400, rejectionBody)
        try {
            assertTrue(error is PromptCacheKeyRejectedException)
            assertEquals(400, error?.status)
            assertTrue(PromptCacheKeyRejection.isRejected(provider.id.toString()))
            assertTrue(error?.message.orEmpty().contains(rejectionBody))
        } finally {
            PromptCacheKeyRejection.clear(provider.id.toString())
        }

        // 没带这个字段的请求、或没点名这个字段的报错，都不算
        assertNull(promptCacheKeyFailure(provider, buildJsonObject { }, 400, rejectionBody))
        assertNull(promptCacheKeyFailure(provider, sent, 400, "model_not_found"))
    }

    @Test
    fun `stored routing names and old provider config stay compatible`() {
        assertEquals(PromptCacheRouting.AUTO, Json.decodeFromString<PromptCacheRouting>("\"auto\""))
        assertEquals(PromptCacheRouting.ENABLED, Json.decodeFromString<PromptCacheRouting>("\"enabled\""))
        assertEquals(PromptCacheRouting.DISABLED, Json.decodeFromString<PromptCacheRouting>("\"disabled\""))

        val legacy = Json.decodeFromString<ProviderSetting.OpenAI>(
            "{\"apiKey\":\"k\",\"baseUrl\":\"https://api.openai.com/v1\"}"
        )
        assertEquals(PromptCacheRouting.AUTO, legacy.promptCacheRouting)
    }
}
