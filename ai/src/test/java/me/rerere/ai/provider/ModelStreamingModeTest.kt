package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStreamingModeTest {
    @Test
    fun `legacy model json remains detectable for settings migration`() {
        val model = Json.decodeFromString<Model>("""{"modelId":"legacy-model"}""")

        assertNull(model.streamOutput)
    }

    @Test
    fun `streaming preference is serialized per model`() {
        val streaming = Model(modelId = "stream-model", streamOutput = true)
        val nonStreaming = Model(modelId = "batch-model", streamOutput = false)

        val restoredStreaming = Json.decodeFromString<Model>(Json.encodeToString(streaming))
        val restoredNonStreaming = Json.decodeFromString<Model>(Json.encodeToString(nonStreaming))

        assertTrue(restoredStreaming.streamOutput == true)
        assertFalse(restoredNonStreaming.streamOutput == true)
    }

    @Test
    fun `models from one provider choose transport independently`() = runBlocking {
        val provider = RecordingProvider()
        val setting = ProviderSetting.OpenAI()
        val prompt = listOf(UIMessage.user("hello"))

        val streamed = provider.generateTextWithModelTransport(
            providerSetting = setting,
            messages = prompt,
            params = TextGenerationParams(
                model = Model(modelId = "stream-model", streamOutput = true),
                customBody = listOf(
                    CustomBody("stream", JsonPrimitive(false)),
                    CustomBody("transport_test", JsonPrimitive("kept")),
                ),
            ),
        )
        val generated = provider.generateTextWithModelTransport(
            providerSetting = setting,
            messages = prompt,
            params = TextGenerationParams(
                model = Model(modelId = "batch-model", streamOutput = false),
                customBody = listOf(
                    CustomBody("STREAM", JsonPrimitive(true)),
                    CustomBody("transport_test", JsonPrimitive("kept")),
                ),
            ),
        )

        assertEquals(1, provider.streamCalls)
        assertEquals(1, provider.generateCalls)
        assertEquals("streamed", streamed.last().toText())
        assertEquals("generated", generated.last().toText())
        assertTrue(provider.receivedCustomBodies.all { bodies -> bodies.none { it.key.equals("stream", true) } })
        assertTrue(provider.receivedCustomBodies.all { bodies -> bodies.any { it.key == "transport_test" } })
    }

    private class RecordingProvider : Provider<ProviderSetting.OpenAI> {
        var streamCalls = 0
        var generateCalls = 0
        val receivedCustomBodies = mutableListOf<List<CustomBody>>()

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            generateCalls++
            receivedCustomBodies += params.customBody
            return TextGenerationResult(
                id = "generated-id",
                model = params.model.modelId,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("generated")),
                ),
            )
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            streamCalls++
            receivedCustomBodies += params.customBody
            return flowOf<StreamChunk>(
                StreamChunk.TextStart("text"),
                StreamChunk.TextDelta("text", "streamed"),
                StreamChunk.TextEnd("text"),
                StreamChunk.Finish(),
            )
        }
    }
}
