package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.handleTextGenerationResult

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported")
    }

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }
}

/**
 * Generates text using the transport selected on [TextGenerationParams.model].
 *
 * The setting belongs to the model, so two models under the same provider can use
 * different transports. [onUpdateMessages] receives progressive streaming updates
 * and one final update for non-streaming responses.
 */
suspend fun <T : ProviderSetting> Provider<T>.generateTextWithModelTransport(
    providerSetting: T,
    messages: List<UIMessage>,
    params: TextGenerationParams,
    responseMessages: List<UIMessage> = messages,
    onUpdateMessages: suspend (List<UIMessage>) -> Unit = {},
): List<UIMessage> {
    // The explicit model setting is authoritative; a legacy custom body must not
    // force the opposite transport and desynchronize the response parser.
    val transportParams = if (params.customBody.any { it.key.equals("stream", ignoreCase = true) }) {
        params.copy(
            customBody = params.customBody.filterNot { it.key.equals("stream", ignoreCase = true) },
        )
    } else {
        params
    }
    if (params.model.streamOutput == false) {
        val updatedMessages = responseMessages.handleTextGenerationResult(
            result = generateText(providerSetting, messages, transportParams),
            model = params.model,
        )
        onUpdateMessages(updatedMessages)
        return updatedMessages
    }

    var updatedMessages = responseMessages
    val streamChunkHandler = StreamChunkHandler(params.model)
    streamText(providerSetting, messages, transportParams).collect { chunk ->
        updatedMessages = streamChunkHandler.handle(updatedMessages, chunk)
        onUpdateMessages(updatedMessages)
    }
    return updatedMessages
}

@Serializable
data class TextGenerationResult(
    val id: String,
    val model: String,
    val message: UIMessage,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
)

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    /** Application-level switch; providers must not send cache controls when this is false. */
    val promptCacheEnabled: Boolean = true,
    /** Stable session-scoped key used by OpenAI implicit caching. */
    val promptCacheKey: String? = null,
    /** Logical prompt supplied by the request builder; omitted from serialization. */
    @Transient val logicalPrompt: LogicalPrompt? = null,
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
