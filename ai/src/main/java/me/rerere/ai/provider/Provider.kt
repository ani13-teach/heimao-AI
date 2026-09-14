package me.rerere.ai.provider

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlin.time.TimeSource
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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
 * 重试状态，供调用方提示用户或清除提示。
 *
 * 请求没有进入重试时不会发出任何状态，因此调用方只会清掉自己写过的提示。
 */
sealed interface RetryStatus {
    /** 本次尝试失败，将在 [delayMillis] 毫秒后进行第 [nextAttempt] 次尝试。 */
    data class Waiting(
        val nextAttempt: Int,
        val delayMillis: Long,
        val cause: Throwable,
    ) : RetryStatus

    /** 重试已经恢复，可以清除等待提示。 */
    data object Recovered : RetryStatus
}

/**
 * Generates text using the transport selected on [TextGenerationParams.model].
 *
 * The setting belongs to the model, so two models under the same provider can use
 * different transports. [onUpdateMessages] receives progressive streaming updates
 * and one final update for non-streaming responses.
 *
 * [retryPolicy] is null by default, which attempts one request exactly once — the behavior
 * every caller had before retries existed. With a policy, a retryable failure or an empty
 * response is retried until the policy runs out, and each attempt starts from [responseMessages]
 * so a failed attempt cannot leak partial output into the next one.
 */
suspend fun <T : ProviderSetting> Provider<T>.generateTextWithModelTransport(
    providerSetting: T,
    messages: List<UIMessage>,
    params: TextGenerationParams,
    responseMessages: List<UIMessage> = messages,
    retryPolicy: RetryPolicy? = null,
    onRetryStatus: (suspend (RetryStatus) -> Unit)? = null,
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
        val updatedMessages = withRetries(
            policy = retryPolicy,
            responseMessages = responseMessages,
            onRetryStatus = onRetryStatus,
            // 非流式失败不会中途更新界面，没有半截内容需要回退
            onDiscardAttempt = {},
            attempt = {
                responseMessages.handleTextGenerationResult(
                    result = generateText(providerSetting, messages, transportParams),
                    model = params.model,
                )
            },
        )
        onUpdateMessages(updatedMessages)
        return updatedMessages
    }

    return withRetries(
        policy = retryPolicy,
        responseMessages = responseMessages,
        onRetryStatus = onRetryStatus,
        // 回退到请求前的消息列表，界面和会话都不留下上一次尝试的半截内容
        onDiscardAttempt = { onUpdateMessages(responseMessages) },
        attempt = {
            // 累积器保存了 part 下标，必须每次尝试都新建
            val streamChunkHandler = StreamChunkHandler(params.model)
            var attemptMessages = responseMessages
            streamText(providerSetting, messages, transportParams).collect { chunk ->
                attemptMessages = streamChunkHandler.handle(attemptMessages, chunk)
                onUpdateMessages(attemptMessages)
            }
            attemptMessages
        },
    )
}

private suspend fun withRetries(
    policy: RetryPolicy?,
    responseMessages: List<UIMessage>,
    onRetryStatus: (suspend (RetryStatus) -> Unit)?,
    onDiscardAttempt: suspend () -> Unit,
    attempt: suspend () -> List<UIMessage>,
): List<UIMessage> {
    val startedAt = TimeSource.Monotonic.markNow()
    var attemptIndex = 1

    while (true) {
        var produced: List<UIMessage>? = null
        var failure: Throwable? = null
        try {
            produced = attempt()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            failure = error
        }

        val result = produced
        if (result != null && result.hasGeneratedContentSince(responseMessages)) {
            if (attemptIndex > 1) {
                onRetryStatus?.invoke(RetryStatus.Recovered)
            }
            return result
        }

        // 失败的尝试没有异常可用时，用一个空回复异常走同一套重试判断
        val cause = failure ?: IOException("empty response")
        val delayMillis = policy?.delayBeforeRetry(
            failedAttempt = attemptIndex,
            elapsedMillis = startedAt.elapsedNow().inWholeMilliseconds,
            error = cause,
        )
        if (delayMillis == null) {
            failure?.let { throw it }
            // 空回复且不再重试：保持加入重试之前的行为，返回拿到的空结果
            println("[generateText] empty response and no retry left")
            return result ?: responseMessages
        }

        onDiscardAttempt()
        onRetryStatus?.invoke(RetryStatus.Waiting(attemptIndex + 1, delayMillis, cause))
        println("[generateText] retry #${attemptIndex + 1} in ${delayMillis}ms (${cause.message})")
        delay(delayMillis)
        attemptIndex++
    }
}

/** 本次尝试是否产生了内容：新增消息里有内容部件，或已有末条消息的内容发生变化。 */
private fun List<UIMessage>.hasGeneratedContentSince(base: List<UIMessage>): Boolean {
    if (size > base.size) {
        return drop(base.size).any { message -> message.parts.any { it.isGeneratedContent() } }
    }
    val baseParts = base.lastOrNull()?.parts.orEmpty()
    return lastOrNull()?.parts.orEmpty()
        .any { part -> part.isGeneratedContent() && baseParts.none { it == part } }
}

private fun UIMessagePart.isGeneratedContent(): Boolean = when (this) {
    is UIMessagePart.Text -> text.isNotBlank()
    is UIMessagePart.Reasoning -> reasoning.isNotBlank()
    is UIMessagePart.Image,
    is UIMessagePart.Video,
    is UIMessagePart.Audio,
    is UIMessagePart.Document,
    is UIMessagePart.Tool,
    is UIMessagePart.ServerTool,
    -> true

    // 已废弃的 part 类型不再由解码器产生，不当作内容处理
    else -> false
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
