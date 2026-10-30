package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PromptCacheTransportTest {
    @Test
    fun `routing switches reach both transports without network or credentials`() = runBlocking {
        val key = conversationPromptCacheKey("assistant", "conversation")
        for (responses in listOf(false, true)) {
            for (stream in listOf(false, true)) {
                for (mode in SessionAffinityMode.entries) {
                    var captured: Request? = null
                    val client = OkHttpClient.Builder().addInterceptor { chain ->
                        captured = chain.request()
                        val responseJson = if (responses) {
                            """{"id":"resp-test","model":"gpt-test","status":"completed","output":[{"id":"msg-test","type":"message","role":"assistant","content":[{"type":"output_text","text":"hello","annotations":[]}]}]}"""
                        } else {
                            """{"id":"chat-test","model":"gpt-test","choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}]}"""
                        }
                        val mediaType = if (stream) "text/event-stream" else "application/json"
                        val data = when {
                            !stream -> responseJson
                            responses -> "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":$responseJson}\n\n"
                            else -> "data: {\"id\":\"chat-test\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        }
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK").header("Content-Type", mediaType)
                            .body(data.toResponseBody(mediaType.toMediaType())).build()
                    }.build()
                    val provider = ProviderSetting.OpenAI(
                        baseUrl = "https://relay.example/v1", apiKey = "test-only",
                        promptCacheKeyMode = PromptCacheKeyMode.ENABLED, sessionAffinity = mode,
                    )
                    val params = TextGenerationParams(
                        model = Model(modelId = "gpt-test"), promptCacheKey = key,
                        customHeaders = listOf(CustomHeader("X-Session-Affinity", "manual")),
                    )
                    val api: OpenAIImpl = if (responses) ResponseAPI(client) else ChatCompletionsAPI(client, KeyRoulette.default())
                    try {
                        if (stream) api.streamText(provider, listOf(UIMessage.user("hello")), params).toList()
                        else api.generateText(provider, listOf(UIMessage.user("hello")), params)
                        val request = captured!!
                        assertEquals("manual", request.header("x-session-affinity"))
                        assertEquals(if (mode == SessionAffinityMode.OPENAI) key else null, request.header("session_id"))
                        assertEquals(if (mode == SessionAffinityMode.OPENROUTER) key else null, request.header("x-session-id"))
                        val buffer = okio.Buffer()
                        request.body!!.writeTo(buffer)
                        val body = buffer.readUtf8()
                        assertTrue(body.contains(key))
                        assertFalse(body.contains("conversation"))
                    } finally {
                        client.dispatcher.executorService.shutdown()
                        client.connectionPool.evictAll()
                    }
                }
            }
        }
    }
}
