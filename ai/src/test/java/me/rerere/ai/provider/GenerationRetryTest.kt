package me.rerere.ai.provider

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRetryTest {
    private val prompt = listOf(UIMessage.user("hello"))

    @Test
    fun `retries a failed streaming request until it succeeds`() = runBlocking {
        val provider = ScriptedProvider(
            streamScript = listOf(
                failing { IOException("connection reset") },
                failing { IOException("connection reset") },
                succeeding("recovered"),
            )
        )

        val result = provider.generateTextWithModelTransport(
            providerSetting = ProviderSetting.OpenAI(),
            messages = prompt,
            params = streamingParams(),
            retryPolicy = policy(),
        )

        assertEquals(3, provider.streamCalls)
        assertEquals("recovered", result.last().toText())
    }

    @Test
    fun `discards partial output from a failed attempt`() = runBlocking {
        val updates = mutableListOf<List<UIMessage>>()
        val provider = ScriptedProvider(
            streamScript = listOf(
                { send ->
                    send(StreamChunk.TextStart("text"))
                    send(StreamChunk.TextDelta("text", "partial"))
                    throw IOException("stream closed after partial output")
                },
                succeeding("complete"),
            )
        )

        val result = provider.generateTextWithModelTransport(
            providerSetting = ProviderSetting.OpenAI(),
            messages = prompt,
            params = streamingParams(),
            retryPolicy = policy(),
            onUpdateMessages = { updates += it },
        )

        assertEquals(2, provider.streamCalls)
        assertEquals("complete", result.last().toText())

        val partialIndex = updates.indexOfFirst { it.last().toText() == "partial" }
        val resetIndex = updates.indexOfFirst { it == prompt }
        assertTrue("半截文本要先出现在更新里，才谈得上回退", partialIndex >= 0)
        assertTrue("重试前必须把消息列表回退到请求前快照", resetIndex > partialIndex)
        assertTrue(
            "回退之后不能再出现上一次尝试的文本",
            updates.drop(resetIndex).none { it.last().toText().contains("partial") },
        )
        assertEquals("complete", updates.last().last().toText())
    }

    @Test
    fun `reports retry waiting and recovery in order`() = runBlocking {
        val statuses = mutableListOf<RetryStatus>()
        val provider = ScriptedProvider(
            streamScript = listOf(
                failing { IOException("boom") },
                succeeding("ok"),
            )
        )

        provider.generateTextWithModelTransport(
            providerSetting = ProviderSetting.OpenAI(),
            messages = prompt,
            params = streamingParams(),
            retryPolicy = RetryPolicy(intervalMillis = 1, maxDurationMillis = 10_000),
            onRetryStatus = { statuses += it },
        )

        assertEquals(2, statuses.size)
        val waiting = statuses[0] as RetryStatus.Waiting
        assertEquals(2, waiting.nextAttempt)
        assertEquals(1L, waiting.delayMillis)
        assertSame(RetryStatus.Recovered, statuses[1])
    }

    @Test
    fun `clears the waiting notice when the attempts run out`() = runBlocking {
        val statuses = mutableListOf<RetryStatus>()
        val provider = ScriptedProvider(
            streamScript = listOf(
                failing { IOException("first") },
                failing { IOException("second") },
            )
        )

        runCatching {
            provider.generateTextWithModelTransport(
                providerSetting = ProviderSetting.OpenAI(),
                messages = prompt,
                params = streamingParams(),
                retryPolicy = RetryPolicy(
                    intervalMillis = 1,
                    maxDurationMillis = 10_000,
                    maxAttempts = 2,
                ),
                onRetryStatus = { statuses += it },
            )
        }

        assertEquals(2, statuses.size)
        assertTrue(statuses[0] is RetryStatus.Waiting)
        assertSame(RetryStatus.Stopped, statuses[1])
    }

    @Test
    fun `clears the waiting notice when the wait is cancelled`() = runBlocking {
        val statuses = mutableListOf<RetryStatus>()
        val waiting = CompletableDeferred<Unit>()
        val provider = ScriptedProvider(
            streamScript = listOf(
                failing { IOException("boom") },
                succeeding("too late"),
            )
        )

        val job = launch {
            provider.generateTextWithModelTransport(
                providerSetting = ProviderSetting.OpenAI(),
                messages = prompt,
                params = streamingParams(),
                retryPolicy = RetryPolicy(intervalMillis = 60_000, maxDurationMillis = 600_000),
                onRetryStatus = {
                    statuses += it
                    if (it is RetryStatus.Waiting) waiting.complete(Unit)
                },
            )
        }

        waiting.await()
        job.cancelAndJoin()

        assertEquals(2, statuses.size)
        assertSame(RetryStatus.Stopped, statuses[1])
        assertEquals(1, provider.streamCalls)
    }

    @Test
    fun `never reports status when no retry happened`() = runBlocking {
        val statuses = mutableListOf<RetryStatus>()
        val provider = ScriptedProvider(streamScript = listOf(succeeding("ok")))

        provider.generateTextWithModelTransport(
            providerSetting = ProviderSetting.OpenAI(),
            messages = prompt,
            params = streamingParams(),
            retryPolicy = policy(),
            onRetryStatus = { statuses += it },
        )

        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `propagates cancellation without another request`() = runBlocking {
        val provider = ScriptedProvider(
            streamScript = listOf(failing { CancellationException("stopped by user") })
        )

        val error = runCatching {
            provider.generateTextWithModelTransport(
                providerSetting = ProviderSetting.OpenAI(),
                messages = prompt,
                params = streamingParams(),
                retryPolicy = policy(),
            )
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(1, provider.streamCalls)
    }

    @Test
    fun `does not retry a failure that cannot recover`() = runBlocking {
        val provider = ScriptedProvider(
            streamScript = listOf(failing { IllegalStateException("Invalid content-type") })
        )

        val error = runCatching {
            provider.generateTextWithModelTransport(
                providerSetting = ProviderSetting.OpenAI(),
                messages = prompt,
                params = streamingParams(),
                retryPolicy = policy(),
            )
        }.exceptionOrNull()

        assertEquals("Invalid content-type", error?.message)
        assertEquals(1, provider.streamCalls)
    }

    @Test
    fun `throws the last failure when the attempt limit is reached`() = runBlocking {
        val provider = ScriptedProvider(
            streamScript = listOf(
                failing { IOException("first") },
                failing { IOException("second") },
            )
        )

        val error = runCatching {
            provider.generateTextWithModelTransport(
                providerSetting = ProviderSetting.OpenAI(),
                messages = prompt,
                params = streamingParams(),
                retryPolicy = RetryPolicy(
                    intervalMillis = 1,
                    maxDurationMillis = 10_000,
                    maxAttempts = 2,
                ),
            )
        }.exceptionOrNull()

        assertEquals("second", error?.message)
        assertEquals(2, provider.streamCalls)
    }

    @Test
    fun `retries an empty streaming response`() = runBlocking {
        val provider = ScriptedProvider(
            streamScript = listOf(
                { send -> send(StreamChunk.Finish()) },
                succeeding("recovered from empty"),
            )
        )

        val result = provider.generateTextWithModelTransport(
            providerSetting = ProviderSetting.OpenAI(),
            messages = prompt,
            params = streamingParams(),
            retryPolicy = policy(),
        )

        assertEquals(2, provider.streamCalls)
        assertEquals("recovered from empty", result.last().toText())
    }

    @Test
    fun `keeps an empty response when no policy allows a retry`() = runBlocking {
        val provider = ScriptedProvider(
            streamScript = listOf({ send -> send(StreamChunk.Finish()) })
        )

        val result = provider.generateTextWithModelTransport(
            providerSetting = ProviderSetting.OpenAI(),
            messages = prompt,
            params = streamingParams(),
        )

        assertEquals(1, provider.streamCalls)
        assertEquals("", result.last().toText())
    }

    @Test
    fun `appending to an existing assistant message counts as content`() = runBlocking {
        val history = listOf(
            UIMessage.user("hello"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(""))),
        )
        val provider = ScriptedProvider(streamScript = listOf(succeeding("resumed")))

        val result = provider.generateTextWithModelTransport(
            providerSetting = ProviderSetting.OpenAI(),
            messages = prompt,
            params = streamingParams(),
            responseMessages = history,
            retryPolicy = policy(),
        )

        assertEquals(1, provider.streamCalls)
        // 内容被并入了已有的助手消息（最后一个是已有的空 part）
        assertEquals(
            listOf("", "resumed"),
            result.last().parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
    }

    @Test
    fun `retries a failed non-streaming request`() = runBlocking {
        val provider = ScriptedProvider(
            streamScript = emptyList(),
            generateScript = listOf(
                { throw IOException("connection reset") },
                {
                    TextGenerationResult(
                        id = "generated-id",
                        model = "batch-model",
                        message = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Text("generated after retry")),
                        ),
                    )
                },
            ),
        )

        val result = provider.generateTextWithModelTransport(
            providerSetting = ProviderSetting.OpenAI(),
            messages = prompt,
            params = TextGenerationParams(
                model = Model(modelId = "batch-model", streamOutput = false),
            ),
            retryPolicy = policy(),
        )

        assertEquals(2, provider.generateCalls)
        assertEquals(0, provider.streamCalls)
        assertEquals("generated after retry", result.last().toText())
    }

    private fun policy() = RetryPolicy(intervalMillis = 1, maxDurationMillis = 10_000)

    private fun streamingParams() =
        TextGenerationParams(model = Model(modelId = "stream-model", streamOutput = true))

    private fun succeeding(text: String): suspend (send: suspend (StreamChunk) -> Unit) -> Unit =
        { send ->
            send(StreamChunk.TextStart("text"))
            send(StreamChunk.TextDelta("text", text))
            send(StreamChunk.TextEnd("text"))
            send(StreamChunk.Finish())
        }

    private fun failing(
        error: () -> Throwable,
    ): suspend (send: suspend (StreamChunk) -> Unit) -> Unit = { throw error() }

    private class ScriptedProvider(
        private val streamScript: List<suspend (send: suspend (StreamChunk) -> Unit) -> Unit>,
        private val generateScript: List<suspend () -> TextGenerationResult> = emptyList(),
    ) : Provider<ProviderSetting.OpenAI> {
        var streamCalls = 0
        var generateCalls = 0

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
            emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            val index = generateCalls++
            val script = generateScript.getOrNull(index)
                ?: error("no scripted generate result for call ${index + 1}")
            return script()
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            val index = streamCalls++
            val script = streamScript.getOrNull(index)
                ?: error("no scripted stream for call ${index + 1}")
            return flow {
                script { chunk -> emit(chunk) }
            }
        }
    }
}
