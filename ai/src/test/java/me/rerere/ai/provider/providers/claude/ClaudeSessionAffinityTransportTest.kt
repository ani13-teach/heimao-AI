package me.rerere.ai.provider.providers.claude

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ClaudeSessionAffinityTransportTest {
    private val key = conversationPromptCacheKey("assistant", "conversation")

    private fun responseBody(index: Int, stream: Boolean): String {
        val reason = if (index == 0) "pause_turn" else "end_turn"
        val text = "part-$index"
        if (!stream) return buildJsonObject {
            put("id", "msg-$index"); put("type", "message"); put("role", "assistant")
            put("model", "claude-test"); put("stop_reason", reason)
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
        }.toString()
        val events = listOf(
            "message_start" to buildJsonObject {
                put("type", "message_start")
                put("message", buildJsonObject {
                    put("id", "msg-$index"); put("role", "assistant"); put("model", "claude-test")
                    put("content", JsonArray(emptyList()))
                })
            },
            "content_block_start" to buildJsonObject {
                put("type", "content_block_start"); put("index", 0)
                put("content_block", buildJsonObject { put("type", "text"); put("text", "") })
            },
            "content_block_delta" to buildJsonObject {
                put("type", "content_block_delta"); put("index", 0)
                put("delta", buildJsonObject { put("type", "text_delta"); put("text", text) })
            },
            "content_block_stop" to buildJsonObject { put("type", "content_block_stop"); put("index", 0) },
            "message_delta" to buildJsonObject {
                put("type", "message_delta")
                put("delta", buildJsonObject { put("stop_reason", reason) })
            },
            "message_stop" to buildJsonObject { put("type", "message_stop") },
        )
        return events.joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }
    }

    @Test(timeout = 30_000)
    fun `native Messages routing stays stable across transports pause turn and tool continuation`() = runBlocking {
        for (stream in listOf(false, true)) {
            for (caching in listOf(false, true)) {
                for (mode in SessionAffinityMode.entries) {
                    val captured = CopyOnWriteArrayList<Request>()
                    val client = OkHttpClient.Builder().addInterceptor { chain ->
                        val index = captured.size
                        captured += chain.request()
                        val mediaType = if (stream) "text/event-stream" else "application/json"
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK").header("Content-Type", mediaType)
                            .body(responseBody(index, stream).toResponseBody(mediaType.toMediaType())).build()
                    }.build()
                    try {
                        val provider = ProviderSetting.Claude(
                            baseUrl = "https://relay.example/v1", apiKey = "test-only",
                            promptCaching = caching, sessionAffinity = mode,
                        )
                        val prompt = LogicalPrompt(
                            system = listOf(UIMessage.system("system")), latestUser = UIMessage.user("hello"),
                        )
                        val params = TextGenerationParams(
                            model = Model(modelId = "claude-test"), logicalPrompt = prompt, promptCacheKey = key,
                            customHeaders = listOf(CustomHeader("X-Session-Affinity", "manual")),
                        )
                        val api = ClaudeProvider(client)
                        suspend fun generate(p: LogicalPrompt) {
                            val requestParams = params.copy(logicalPrompt = p)
                            if (stream) {
                                val chunks = api.streamText(provider, p.toMessages(), requestParams).toList()
                                assertEquals("end_turn", chunks.filterIsInstance<StreamChunk.Finish>().last().finishReason)
                            } else {
                                assertEquals("end_turn", api.generateText(provider, p.toMessages(), requestParams).finishReason)
                            }
                        }
                        generate(prompt)
                        assertEquals(2, captured.size)
                        val toolMessage = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Tool(
                                toolCallId = "tool-1", toolName = "lookup", input = "{}",
                                output = listOf(UIMessagePart.Text("tool result")),
                            )),
                        )
                        generate(prompt.copy(currentToolLoop = listOf(toolMessage)))
                        assertEquals(3, captured.size)
                        captured.forEach { request ->
                            assertEquals("/v1/messages", request.url.encodedPath)
                            assertEquals("test-only", request.header("x-api-key"))
                            assertEquals("2023-06-01", request.header("anthropic-version"))
                            assertEquals(listOf("manual"), request.headers.values("x-session-affinity"))
                            assertEquals(if (mode == SessionAffinityMode.OPENAI) key else null, request.header("session_id"))
                            assertEquals(if (mode == SessionAffinityMode.OPENAI) key else null, request.header("x-client-request-id"))
                            assertEquals(if (mode == SessionAffinityMode.OPENROUTER) key else null, request.header("x-session-id"))
                            val buffer = okio.Buffer()
                            request.body!!.writeTo(buffer)
                            val body = buffer.readUtf8()
                            assertFalse(body.contains("prompt_cache_key"))
                            assertFalse(body.contains(key))
                            assertEquals(caching, body.contains("cache_control"))
                        }
                        val buffer = okio.Buffer()
                        captured.last().body!!.writeTo(buffer)
                        val toolBody = buffer.readUtf8()
                        assertTrue(toolBody.contains("tool_use"))
                        assertTrue(toolBody.contains("tool_result"))
                    } finally {
                        client.dispatcher.executorService.shutdown()
                        client.connectionPool.evictAll()
                    }
                }
            }
        }
    }

    @Test(timeout = 15_000)
    fun `routing does not hide upstream errors or trigger extra requests`() = runBlocking {
        for (stream in listOf(false, true)) {
            val captured = CopyOnWriteArrayList<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                captured += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(400).message("Bad Request").body(
                        """{"error":{"type":"invalid_request_error","message":"invalid request"}}"""
                            .toResponseBody("application/json".toMediaType())
                    ).build()
            }.build()
            try {
                val provider = ProviderSetting.Claude(
                    baseUrl = "https://relay.example/v1", apiKey = "test-only", sessionAffinity = SessionAffinityMode.OPENAI,
                )
                val params = TextGenerationParams(model = Model(modelId = "claude-test"), promptCacheKey = key)
                val api = ClaudeProvider(client)
                val failure = runCatching {
                    if (stream) api.streamText(provider, listOf(UIMessage.user("hello")), params).toList()
                    else api.generateText(provider, listOf(UIMessage.user("hello")), params)
                }.exceptionOrNull()
                assertNotNull(failure)
                assertEquals(1, captured.size)
                assertEquals(key, captured.single().header("session_id"))
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}
