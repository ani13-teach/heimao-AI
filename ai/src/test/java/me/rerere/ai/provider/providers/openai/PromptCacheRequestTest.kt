package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ClaudeMemoryCacheBlock
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.PromptCacheKeyMode
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.conversationPromptCacheKey
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class PromptCacheRequestTest {
    private lateinit var chat: ChatCompletionsAPI
    private lateinit var responses: ResponseAPI

    @Before
    fun setUp() {
        chat = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        responses = ResponseAPI(OkHttpClient())
    }

    private fun buildChatRequest(
        params: TextGenerationParams,
        messages: List<UIMessage>,
        provider: ProviderSetting.OpenAI = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(
            chat,
            messages,
            params,
            provider,
            false,
        ) as JsonObject
    }

    @Test
    fun `both adapters honor automatic model and custom cache key policies`() {
        val messages = listOf(UIMessage.user("hello"))
        val key = conversationPromptCacheKey("assistant", "conversation")
        val params = TextGenerationParams(model = Model(modelId = "gpt-test"), promptCacheKey = key)
        val proxy = ProviderSetting.OpenAI(baseUrl = "https://relay.example/v1")
        fun bodies(provider: ProviderSetting.OpenAI, p: TextGenerationParams) = listOf(
            buildChatRequest(p, messages, provider), responses.buildRequestBody(provider, messages, p, false),
            responses.buildRequestBody(provider, messages, p, true),
        )
        bodies(ProviderSetting.OpenAI(), params).forEach { assertEquals(key, it["prompt_cache_key"]?.jsonPrimitive?.content) }
        bodies(proxy, params).forEach { assertFalse(it.containsKey("prompt_cache_key")) }
        bodies(proxy.copy(promptCacheKeyMode = PromptCacheKeyMode.ENABLED), params).forEach {
            assertEquals(key, it["prompt_cache_key"]?.jsonPrimitive?.content)
        }
        bodies(ProviderSetting.OpenAI(), params.copy(model = params.model.copy(promptCacheKeyEnabled = false))).forEach {
            assertFalse(it.containsKey("prompt_cache_key"))
        }
        bodies(ProviderSetting.OpenAI(), params.copy(customBody = listOf(CustomBody("prompt_cache_key", JsonPrimitive("manual"))))).forEach {
            assertEquals("manual", it["prompt_cache_key"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `Responses and Chat adapters use explicit logical order without explicit cache boundaries`() {
        val prompt = LogicalPrompt(
            system = listOf(UIMessage.system("system")),
            history = listOf(UIMessage.user("history")),
            transientContext = listOf(UIMessage.user("retrieval")),
            memory = listOf(UIMessage.system("memory")),
            claudeMemoryCacheBlocks = listOf(ClaudeMemoryCacheBlock("claude base")),
            claudeMemoryDelta = listOf(UIMessage.user("claude patch")),
            latestUser = UIMessage.user("latest"),
        )
        val params = TextGenerationParams(
            model = Model(modelId = "gpt-test"),
            logicalPrompt = prompt,
        )
        val messages = prompt.toMessages()
        val chatBody = buildChatRequest(params, messages)
        val responseBody = responses.buildRequestBody(
            ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            messages,
            params,
            stream = false,
        )

        assertFalse(chatBody.containsKey("cache_control"))
        assertFalse(responseBody.containsKey("cache_control"))

        val chatText = chatBody["messages"]!!.jsonArray.mapNotNull { message ->
            message.jsonObject["content"]?.jsonPrimitive?.content
        }
        assertEquals(listOf("system", "memory", "history", "retrieval", "latest"), chatText)

        val responseText = responseBody["input"]!!.jsonArray.mapNotNull { item ->
            val content = item.jsonObject["content"] ?: return@mapNotNull null
            (content as? JsonPrimitive)?.contentOrNull ?: content.jsonArray.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
        }
        assertEquals(listOf("memory", "history", "retrieval", "latest"), responseText)
        assertEquals("system", responseBody["instructions"]?.jsonPrimitive?.content)
    }
}
