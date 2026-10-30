package me.rerere.ai.provider

import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class PromptCachePolicyTest {
    private val key = conversationPromptCacheKey("assistant", "conversation")
    private val params = TextGenerationParams(model = Model(modelId = "gpt-test"), promptCacheKey = key)
    private val proxy = ProviderSetting.OpenAI(baseUrl = "https://relay.example/v1")

    @Test
    fun `identity is stable opaque scoped and within API limit`() {
        assertEquals(key, conversationPromptCacheKey("assistant", "conversation"))
        assertNotEquals(key, conversationPromptCacheKey("other", "conversation"))
        assertNotEquals(key, conversationPromptCacheKey("assistant", "other"))
        assertNotEquals(conversationPromptCacheKey("ab", "c"), conversationPromptCacheKey("a", "bc"))
        assertTrue(key.matches(Regex("hm-[0-9a-f]{32}")))
    }

    @Test
    fun `auto enables only exact official HTTPS host and model overrides channel`() {
        assertTrue(ProviderSetting.OpenAI().usesPromptCacheKey(params.model))
        for (url in listOf(proxy.baseUrl, "https://api.openai.com.evil/v1", "http://api.openai.com/v1")) {
            assertFalse(proxy.copy(baseUrl = url).usesPromptCacheKey(params.model))
        }
        assertTrue(proxy.copy(promptCacheKeyMode = PromptCacheKeyMode.ENABLED).usesPromptCacheKey(params.model))
        assertFalse(ProviderSetting.OpenAI().usesPromptCacheKey(params.model.copy(promptCacheKeyEnabled = false)))
        assertTrue(proxy.usesPromptCacheKey(params.model.copy(promptCacheKeyEnabled = true)))
        assertFalse(proxy.copy(promptCacheKeyMode = PromptCacheKeyMode.DISABLED).usesPromptCacheKey(params.model))
    }

    @Test
    fun `key injection preserves all caller owned values and unrelated fields`() {
        val provider = ProviderSetting.OpenAI()
        val body = buildJsonObject { put("model", "gpt-test") }
        val injected = body.withPromptCacheKey(provider, params)
        assertEquals(key, injected["prompt_cache_key"]?.jsonPrimitive?.content)
        assertEquals(1, body.size)
        assertSame(body, body.withPromptCacheKey(proxy, params))
        assertSame(body, body.withPromptCacheKey(provider, params.copy(promptCacheKey = null)))
        for (name in listOf("prompt_cache_key", "promptCacheKey")) {
            for (value in listOf(JsonPrimitive("custom"), JsonPrimitive(""), JsonNull)) {
                val custom = JsonObject(body + (name to value))
                assertSame(custom, custom.withPromptCacheKey(provider, params))
            }
        }
    }

    @Test
    fun `routing toggle is independent defaults off and preserves headers case insensitively`() {
        fun request(provider: ProviderSetting.OpenAI, p: TextGenerationParams = params) = Request.Builder()
            .url(provider.baseUrl).header("X-SESSION-AFFINITY", "manual")
            .withSessionAffinity(provider, p).build()
        assertNull(request(proxy).header("session_id"))
        assertEquals("manual", request(proxy).header("x-session-affinity"))
        val enabled = request(proxy.copy(sessionAffinity = SessionAffinityMode.OPENAI))
        assertEquals(key, enabled.header("session_id"))
        assertEquals(key, enabled.header("x-client-request-id"))
        assertEquals(listOf("manual"), enabled.headers.values("x-session-affinity"))
        assertNull(enabled.header("x-session-id"))
        val router = request(proxy.copy(sessionAffinity = SessionAffinityMode.OPENROUTER))
        assertEquals(key, router.header("x-session-id"))
        assertNull(router.header("session_id"))
        assertNull(request(ProviderSetting.OpenAI(sessionAffinity = SessionAffinityMode.OPENAI)).header("session_id"))
        assertNull(request(proxy.copy(sessionAffinity = SessionAffinityMode.OPENAI), params.copy(promptCacheKey = null)).header("session_id"))
    }

    @Test
    fun `Claude route headers are opt in independent of caching and preserve all manual formats`() {
        fun request(provider: ProviderSetting, p: TextGenerationParams = params) = Request.Builder()
            .url("https://relay.example/v1/messages")
            .header("X-Session-Id", "manual-router")
            .header("SESSION_ID", "manual-session")
            .withSessionAffinity(provider, p).build()
        val claude = ProviderSetting.Claude(baseUrl = "https://relay.example/v1")
        assertNull(request(claude).header("x-session-affinity"))
        assertNull(request(claude.copy(promptCaching = true)).header("x-session-affinity"))
        for (caching in listOf(false, true)) {
            val enabled = claude.copy(promptCaching = caching, sessionAffinity = SessionAffinityMode.OPENAI)
            val req = request(enabled, params.copy(model = params.model.copy(promptCacheKeyEnabled = false)))
            assertEquals(listOf("manual-session"), req.headers.values("session_id"))
            assertEquals(key, req.header("x-session-affinity"))
            assertEquals(key, req.header("x-client-request-id"))
            assertNull(request(enabled, params.copy(promptCacheKey = null)).header("x-session-affinity"))
        }
        val router = request(claude.copy(sessionAffinity = SessionAffinityMode.OPENROUTER))
        assertEquals(listOf("manual-router"), router.headers.values("x-session-id"))
        assertNull(router.header("x-session-affinity"))
        assertNull(request(ProviderSetting.Claude(sessionAffinity = SessionAffinityMode.OPENAI)).header("x-session-affinity"))
        assertNull(request(ProviderSetting.Google()).header("x-session-affinity"))
    }

    @Test
    fun `old channel and model settings retain conservative defaults`() {
        val json = Json { ignoreUnknownKeys = true }
        val provider = json.decodeFromString<ProviderSetting.OpenAI>("{}")
        assertEquals(PromptCacheKeyMode.AUTO, provider.promptCacheKeyMode)
        assertEquals(SessionAffinityMode.OFF, provider.sessionAffinity)
        val claude = json.decodeFromString<ProviderSetting.Claude>("{}")
        assertEquals(SessionAffinityMode.OFF, claude.sessionAffinity)
        for (mode in SessionAffinityMode.entries) {
            val configured = claude.copy(promptCaching = true, sessionAffinity = mode)
            assertEquals(configured, json.decodeFromString<ProviderSetting.Claude>(json.encodeToString(ProviderSetting.Claude.serializer(), configured)))
            assertEquals(mode, (configured.copyProvider(name = "renamed") as ProviderSetting.Claude).sessionAffinity)
        }
        assertNull(json.decodeFromString<Model>("{}").promptCacheKeyEnabled)
        assertFalse(json.encodeToString(TextGenerationParams.serializer(), params).contains(key))
        assertEquals(provider, json.decodeFromString<ProviderSetting.OpenAI>(json.encodeToString(ProviderSetting.OpenAI.serializer(), provider)))
    }
}
