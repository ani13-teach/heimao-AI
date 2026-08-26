package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            false,
        ) as JsonObject
    }

    @Test
    fun `enabled OpenAI requests carry only stable session key`() {
        val key = "chat_" + "a".repeat(64)
        val params = TextGenerationParams(
            model = Model(modelId = "gpt-test"),
            promptCacheEnabled = true,
            promptCacheKey = key,
        )
        val body = buildChatRequest(params, listOf(UIMessage.user("hello")))

        assertEquals(key, body["prompt_cache_key"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("cache_control"))
        assertTrue(body["messages"]!!.jsonArray.isNotEmpty())
        body["messages"]!!.jsonArray.forEach { message ->
            message.jsonObject.forEach { (_, value) ->
                assertFalse(value.toString().contains("cache_control"))
            }
        }
    }

    @Test
    fun `disabled OpenAI requests omit prompt cache key`() {
        val params = TextGenerationParams(
            model = Model(modelId = "gpt-test"),
            promptCacheEnabled = false,
            promptCacheKey = "chat_" + "b".repeat(64),
        )
        val chatBody = buildChatRequest(params, listOf(UIMessage.user("hello")))
        val responseBody = responses.buildRequestBody(
            ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            listOf(UIMessage.user("hello")),
            params,
            stream = false,
        )

        assertNull(chatBody["prompt_cache_key"])
        assertNull(responseBody["prompt_cache_key"])
        assertFalse(responseBody.containsKey("cache_control"))
    }

    @Test
    fun `Responses and Chat adapters use explicit logical order without explicit cache boundaries`() {
        val prompt = LogicalPrompt(
            system = listOf(UIMessage.system("system")),
            history = listOf(UIMessage.user("history")),
            transientContext = listOf(UIMessage.user("retrieval")),
            memory = listOf(UIMessage.system("memory")),
            latestUser = UIMessage.user("latest"),
        )
        val params = TextGenerationParams(
            model = Model(modelId = "gpt-test"),
            promptCacheEnabled = true,
            promptCacheKey = "chat_" + "c".repeat(64),
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

        assertEquals(params.promptCacheKey, chatBody["prompt_cache_key"]?.jsonPrimitive?.content)
        assertEquals(params.promptCacheKey, responseBody["prompt_cache_key"]?.jsonPrimitive?.content)
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
