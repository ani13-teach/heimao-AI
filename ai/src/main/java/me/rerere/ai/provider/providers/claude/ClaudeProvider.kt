package me.rerere.ai.provider.providers.claude

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageNormalizer
import me.rerere.ai.core.merge
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.inPromptOrder
import me.rerere.ai.provider.CacheBoundary
import me.rerere.ai.provider.PromptCacheApi
import me.rerere.ai.provider.observePromptCacheRequest
import me.rerere.ai.provider.withSessionAffinity
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.LogicalPrompt
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.httpResponseFailure
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ClaudeProvider"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_PAUSE_TURN = "pause_turn"
private const val MAX_PAUSE_TURN_CONTINUATIONS = 5

internal suspend fun generateClaudeWithPauseTurn(
    messages: List<UIMessage>,
    model: Model,
    maxContinuations: Int = MAX_PAUSE_TURN_CONTINUATIONS,
    request: suspend (List<UIMessage>) -> TextGenerationResult,
): TextGenerationResult {
    require(maxContinuations >= 0) { "maxContinuations must be non-negative" }

    var requestMessages = messages
    var combinedMessage = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
    var combinedUsage: TokenUsage? = null
    var nextServerToolBlockIndex = 0

    repeat(maxContinuations + 1) { continuationCount ->
        val rawResult = request(requestMessages)
        val result = rawResult.copy(
            message = rawResult.message.rebaseClaudeServerToolIndexes(nextServerToolBlockIndex),
        )
        result.message.maxClaudeServerToolIndex()?.let { maxIndex ->
            nextServerToolBlockIndex = maxOf(nextServerToolBlockIndex, maxIndex + 1)
        }
        combinedMessage = listOf(combinedMessage)
            .handleTextGenerationResult(result, model)
            .last()
        combinedUsage = combinedUsage.sum(result.usage)

        if (result.finishReason != CLAUDE_PAUSE_TURN || continuationCount == maxContinuations) {
            return result.copy(
                message = combinedMessage.copy(usage = combinedUsage),
                usage = combinedUsage,
            )
        }

        // pause_turn 要求原样回放当前 assistant 响应，不能额外插入 "Continue" user 消息。
        requestMessages = requestMessages.handleTextGenerationResult(result, model)
    }

    error("unreachable")
}

internal fun streamClaudeWithPauseTurn(
    messages: List<UIMessage>,
    model: Model,
    maxContinuations: Int = MAX_PAUSE_TURN_CONTINUATIONS,
    request: (List<UIMessage>) -> Flow<StreamChunk>,
): Flow<StreamChunk> = flow {
    require(maxContinuations >= 0) { "maxContinuations must be non-negative" }

    var requestMessages = messages
    var completedUsage: TokenUsage? = null
    var nextServerToolBlockIndex = 0

    repeat(maxContinuations + 1) { continuationCount ->
        val handler = StreamChunkHandler(model)
        var responseMessages = requestMessages
        var passUsage: TokenUsage? = null
        var finish: StreamChunk.Finish? = null
        val responseIndexOffset = nextServerToolBlockIndex

        request(requestMessages).collect { rawChunk ->
            val chunk = rawChunk.rebaseClaudeServerToolIndexes(responseIndexOffset)
            chunk.maxClaudeServerToolIndex()?.let { maxIndex ->
                nextServerToolBlockIndex = maxOf(nextServerToolBlockIndex, maxIndex + 1)
            }
            responseMessages = handler.handle(responseMessages, chunk)
            when (chunk) {
                is StreamChunk.Usage -> {
                    passUsage = passUsage.merge(chunk.usage)
                    completedUsage.sum(passUsage)?.let { emit(StreamChunk.Usage(it)) }
                }

                is StreamChunk.Finish -> {
                    finish = chunk
                    if (chunk.finishReason != CLAUDE_PAUSE_TURN ||
                        continuationCount == maxContinuations
                    ) {
                        emit(chunk)
                    }
                }

                else -> emit(chunk)
            }
        }

        if (finish?.finishReason != CLAUDE_PAUSE_TURN || continuationCount == maxContinuations) {
            return@flow
        }

        completedUsage = completedUsage.sum(passUsage)
        requestMessages = responseMessages
    }
}

/**
 * Claude content block index 只在单次响应内有效。pause_turn 会把多次响应合并为一个逻辑
 * assistant turn，因此在合并前将后续响应的 server tool index 偏移到同一序号空间。
 */
private fun UIMessage.rebaseClaudeServerToolIndexes(offset: Int): UIMessage = copy(
    parts = parts.map { part ->
        if (part !is UIMessagePart.ServerTool) return@map part
        part.copy(metadata = part.metadata.rebaseClaudeServerToolIndexes(offset))
    },
)

private fun StreamChunk.rebaseClaudeServerToolIndexes(offset: Int): StreamChunk = when (this) {
    is StreamChunk.ServerToolStart -> copy(metadata = metadata.rebaseClaudeServerToolIndexes(offset))
    is StreamChunk.ServerToolEnd -> copy(metadata = metadata.rebaseClaudeServerToolIndexes(offset))
    else -> this
}

private fun JsonObject?.rebaseClaudeServerToolIndexes(offset: Int): JsonObject? {
    if (this == null) return null
    val metadata = runCatching {
        json.decodeFromJsonElement<ServerToolMetadata>(this)
    }.getOrNull()?.takeIf { it.protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
        ?: return this
    if (metadata.callIndex == null && metadata.resultIndex == null) return this
    return JsonObject(this + metadata.rebaseIndexes(offset).toMetadata())
}

private fun ServerToolMetadata.rebaseIndexes(offset: Int): ServerToolMetadata = copy(
    callIndex = callIndex?.plus(offset),
    resultIndex = resultIndex?.plus(offset),
)

private fun UIMessage.maxClaudeServerToolIndex(): Int? = parts
    .filterIsInstance<UIMessagePart.ServerTool>()
    .mapNotNull { part ->
        part.metadataAs<ServerToolMetadata>()
            ?.takeIf { it.protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
            ?.maxIndex()
    }
    .maxOrNull()

private fun StreamChunk.maxClaudeServerToolIndex(): Int? {
    val metadata = when (this) {
        is StreamChunk.ServerToolStart -> metadata
        is StreamChunk.ServerToolEnd -> metadata
        else -> null
    } ?: return null
    return runCatching {
        json.decodeFromJsonElement<ServerToolMetadata>(metadata)
    }.getOrNull()?.takeIf { it.protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }?.maxIndex()
}

private fun ServerToolMetadata.maxIndex(): Int? = listOfNotNull(callIndex, resultIndex).maxOrNull()

private fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null) return other
    if (other == null) return this
    return TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        totalTokens = totalTokens + other.totalTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
        cacheUsageReported = me.rerere.ai.core.mergeCacheUsageReported(cacheUsageReported, other.cacheUsageReported),
        provider = other.provider ?: provider,
        model = other.model ?: model,
    )
}

class ClaudeProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Claude> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id

                Model(
                    modelId = id,
                    displayName = displayName,
                )
            }
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> {
        error("Claude provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        generateClaudeWithPauseTurn(messages, params.model) { requestMessages ->
            generateTextOnce(providerSetting, requestMessages, params)
        }
    }

    private suspend fun generateTextOnce(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val requestBody = buildMessageRequest(providerSetting, messages, params)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .configureReferHeaders(providerSetting.baseUrl)
            .withSessionAffinity(providerSetting, params)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw HttpException("Failed to get response: ${response.code} ${response.body?.string()}", response.code)
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val content = bodyJson["content"]?.jsonArray ?: JsonArray(emptyList())
        val stopReason = bodyJson["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val usage = parseTokenUsage(bodyJson)

        return TextGenerationResult(
            id = id,
            model = model,
            message = parseMessage(content),
            finishReason = stopReason,
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = streamClaudeWithPauseTurn(messages, params.model) { requestMessages ->
        streamTextOnce(providerSetting, requestMessages, params)
    }

    private fun streamTextOnce(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildMessageRequest(providerSetting, messages, params, stream = true)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .withSessionAffinity(providerSetting, params)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        requestBody["messages"]!!.jsonArray.forEach {
            Log.i(TAG, "streamText: $it")
        }

        val decoder = ClaudeStreamDecoder()

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: type=$type, data=$data")
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                Log.e(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.i(TAG, "Error response: $bodyElement")
                        exception = bodyElement.parseErrorDetail(response?.code)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    // 错误体不是 JSON（例如网关的 HTML 错误页）时也得报错，
                    // 拿 null 关流会变成空回复，上层看不到任何异常。
                    close(exception ?: httpResponseFailure(response, bodyRaw))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                sendChunks(decoder.onClosed())
                close()
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "Closing eventSource")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    /**
     * Keep the explicit logical sections for the initial request and append Claude's
     * pause_turn response only to the current tool-loop tail on continuations.
     */
    private fun logicalPromptForRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): LogicalPrompt {
        val configured = params.logicalPrompt ?: return LogicalPrompt.fromMessages(messages, params.tools)
        val configuredMessages = configured.toMessages()
        if (messages == configuredMessages) return configured

        // A new assistant response is appended after the logical prompt on the first
        // pause_turn continuation. Subsequent continuations replace/merge the last assistant
        // message in place, so handle that same-size case separately.
        if (messages.size > configuredMessages.size &&
            messages.take(configuredMessages.size) == configuredMessages
        ) {
            return configured.copy(
                currentToolLoop = configured.currentToolLoop +
                    messages.drop(configuredMessages.size),
            )
        }
        if (configured.currentToolLoop.isNotEmpty() &&
            messages.size == configuredMessages.size &&
            messages.dropLast(1) == configuredMessages.dropLast(1)
        ) {
            return configured.copy(
                currentToolLoop = configured.currentToolLoop.dropLast(1) + messages.last(),
            )
        }

        // Direct provider callers may supply a different list than the request builder. The
        // legacy reconstruction is the safest fallback for those calls.
        return LogicalPrompt.fromMessages(messages, params.tools)
    }

    private fun buildMessageRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        // Prefer the request builder's explicit section boundaries. Claude pause_turn appends
        // the previous response to the flat message list, so merge only that continuation tail
        // into the current tool-loop section instead of reclassifying transient context as history.
        val prompt = logicalPromptForRequest(messages, params)
        val renderedMessages = renderMessages(prompt)
        val memorySystemTexts = prompt.claudeMemoryCacheBlocks?.map { it.text }
            ?: prompt.memorySnapshot.flatMap { message ->
                message.parts.mapNotNull { part ->
                    (part as? UIMessagePart.Text)?.text?.takeIf { it.isNotBlank() }
                }
            }
        val systemBlocks = prompt.system.flatMap { message ->
            message.parts.mapNotNull { part ->
                (part as? UIMessagePart.Text)
                    ?.takeIf { it.text.isNotBlank() }
                    ?.let { textPart ->
                        buildJsonObject {
                            put("type", "text")
                            put("text", textPart.text)
                        }
                    }
            }
        } + memorySystemTexts.map { text ->
            buildJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
        val stableSystemBlockCount = prompt.system.sumOf { message ->
            message.parts.count { part -> part is UIMessagePart.Text && part.text.isNotBlank() }
        }
        val memorySnapshotBlockCount = memorySystemTexts.size
        val toolDefinitions = buildToolDefinitions(params)
        val layout = ClaudeRequestLayout(
            history = renderedMessages.locationsFor(ClaudePromptRegion.HISTORY),
            transient = renderedMessages.locationsFor(ClaudePromptRegion.TRANSIENT),
            memory = renderedMessages.locationsFor(ClaudePromptRegion.MEMORY),
            memoryDelta = renderedMessages.locationsFor(ClaudePromptRegion.MEMORY_DELTA),
            memoryPresent = memorySnapshotBlockCount > 0 || prompt.hasMemory,
            memorySnapshot = (stableSystemBlockCount until systemBlocks.size)
                .map(ClaudeCacheLocation::System),
            system = (0 until stableSystemBlockCount).map(ClaudeCacheLocation::System),
            tools = toolDefinitions.indices.map(ClaudeCacheLocation::Tool),
        )

        var request = buildJsonObject {
            put("model", params.model.modelId)
            put("messages", JsonArray(renderedMessages.map { it.body }))
            put("max_tokens", params.maxTokens ?: 64_000)

            if (params.temperature != null && !params.reasoningLevel.isEnabled) put(
                "temperature",
                params.temperature
            )
            if (params.topP != null) put("top_p", params.topP)

            put("stream", stream)

            if (systemBlocks.isNotEmpty()) put("system", JsonArray(systemBlocks))

            // Anthropic's adaptive thinking configuration is independent of prompt caching.
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                when (params.reasoningLevel) {
                    ReasoningLevel.OFF -> {
                        put("thinking", buildJsonObject { put("type", "disabled") })
                    }

                    ReasoningLevel.AUTO -> {
                        put("thinking", buildJsonObject {
                            put("type", "adaptive")
                            put("display", "summarized")
                        })
                    }

                    else -> {
                        put("thinking", buildJsonObject {
                            put("type", "adaptive")
                            put("display", "summarized")
                        })
                        put("output_config", buildJsonObject {
                            put("effort", params.reasoningLevel.effort)
                        })
                    }
                }
            }

            if (toolDefinitions.isNotEmpty()) {
                put("tools", JsonArray(toolDefinitions))
            }
        }.mergeCustomBody(params.customBody)

        if (!providerSetting.promptCaching) return request.observePromptCacheRequest(params, PromptCacheApi.CLAUDE, logicalPrompt = prompt)
        // Custom body values can replace the arrays used by the logical layout. Applying
        // cached indices after such a replacement could attach a boundary to unrelated
        // content, so leave the caller-owned wire shape untouched and skip caching.
        if (params.customBody.any { it.key == "messages" || it.key == "system" || it.key == "tools" }) {
            Log.w(TAG, "[PromptCache] skipped: custom body overrides prompt layout")
            return request.observePromptCacheRequest(params, PromptCacheApi.CLAUDE, layoutSkipped = true, logicalPrompt = prompt)
        }

        val systemResult = applyCacheBoundary(
            request = request,
            boundary = CacheBoundary.SYSTEM_END,
            layout = layout,
            promptCacheTtl = providerSetting.promptCacheTtl,
        )
        request = systemResult.request
        val memoryResult = applyCacheBoundary(
            request = request,
            boundary = CacheBoundary.MEMORY_END,
            layout = layout,
            promptCacheTtl = providerSetting.promptCacheTtl,
        )
        request = memoryResult.request
        val historyResult = applyCacheBoundary(
            request = request,
            boundary = CacheBoundary.HISTORY_END,
            layout = layout,
            promptCacheTtl = providerSetting.promptCacheTtl,
        )
        request = historyResult.request
        return request.observePromptCacheRequest(
            params, PromptCacheApi.CLAUDE,
            appliedBoundaries = buildList {
                if (systemResult.applied) add(CacheBoundary.SYSTEM_END)
                if (memoryResult.applied) add(CacheBoundary.MEMORY_END)
                if (historyResult.applied) add(CacheBoundary.HISTORY_END)
            },
            logicalPrompt = prompt,
        )
    }

    private fun buildToolDefinitions(params: TextGenerationParams): List<JsonObject> = buildList {
        val useFunctionTools =
            params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
        if (useFunctionTools) {
            params.tools.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", json.encodeToJsonElement(tool.parameters()))
                })
            }
        }
        params.model.tools.inPromptOrder().forEach { builtInTool ->
            when (builtInTool) {
                BuiltInTools.Search -> add(buildJsonObject {
                    put("type", "web_search_20250305")
                    put("name", "web_search")
                })
                BuiltInTools.UrlContext,
                BuiltInTools.ImageGeneration,
                    -> Unit
            }
        }
    }

    private fun cacheControlEphemeral(promptCacheTtl: ClaudePromptCacheTtl) = buildJsonObject {
        put("type", "ephemeral")
        promptCacheTtl.apiValue?.let { put("ttl", it) }
    }

    private fun renderMessages(prompt: LogicalPrompt): List<RenderedClaudeMessage> = buildList {
        fun addSection(messages: List<UIMessage>, region: ClaudePromptRegion) {
            messages
                .filter { it.isValidToUpload() }
                .forEach { message ->
                    val wireMessage = message.asContextMessage()
                    val rendered = if (wireMessage.role == MessageRole.ASSISTANT) {
                        buildJsonArray { addAssistantMessage(wireMessage) }
                    } else {
                        buildJsonArray { addUserMessage(wireMessage) }
                    }
                    rendered.forEach { add(RenderedClaudeMessage(it.jsonObject, region)) }
                }
        }

        addSection(prompt.history, ClaudePromptRegion.HISTORY)
        addSection(prompt.transientContext, ClaudePromptRegion.TRANSIENT)
        addSection(prompt.memory, ClaudePromptRegion.MEMORY)
        addSection(prompt.memoryDelta, ClaudePromptRegion.MEMORY_DELTA)
        addSection(prompt.claudeMemoryDelta, ClaudePromptRegion.CLAUDE_MEMORY_DELTA)
        prompt.latestUser?.let { addSection(listOf(it), ClaudePromptRegion.CURRENT) }
        addSection(prompt.currentToolLoop, ClaudePromptRegion.CURRENT)
    }

    /**
     * Compatibility renderer for existing provider message tests. Production requests use
     * [buildMessageRequest], where both logical boundaries and the top-level sections are
     * available. This helper intentionally only renders the message timeline.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun buildMessages(
        messages: List<UIMessage>,
        promptCaching: Boolean,
        promptCacheTtl: ClaudePromptCacheTtl,
    ): JsonArray {
        val prompt = LogicalPrompt.fromMessages(messages)
        val rendered = renderMessages(prompt)
        if (!promptCaching) return JsonArray(rendered.map { it.body })

        val layout = ClaudeRequestLayout(
            history = rendered.locationsFor(ClaudePromptRegion.HISTORY),
            transient = rendered.locationsFor(ClaudePromptRegion.TRANSIENT),
            memory = rendered.locationsFor(ClaudePromptRegion.MEMORY),
            memoryDelta = rendered.locationsFor(ClaudePromptRegion.MEMORY_DELTA),
            memoryPresent = prompt.hasMemory,
            memorySnapshot = emptyList(),
            system = emptyList(),
            tools = emptyList(),
        )
        val request = buildJsonObject { put("messages", JsonArray(rendered.map { it.body })) }
        return applyCacheBoundary(
            request = request,
            boundary = CacheBoundary.HISTORY_END,
            layout = layout,
            promptCacheTtl = promptCacheTtl,
        ).request["messages"]?.jsonArray ?: JsonArray(emptyList())
    }

    private fun UIMessage.asContextMessage(): UIMessage =
        if (role == MessageRole.SYSTEM) copy(role = MessageRole.USER) else this

    private fun List<RenderedClaudeMessage>.locationsFor(
        region: ClaudePromptRegion
    ): List<ClaudeCacheLocation> = flatMapIndexed { messageIndex, rendered ->
        if (rendered.region != region) return@flatMapIndexed emptyList()
        rendered.body["content"]?.jsonArray.orEmpty().indices.map { blockIndex ->
            ClaudeCacheLocation.Message(messageIndex, blockIndex)
        }
    }

    private data class RenderedClaudeMessage(
        val body: JsonObject,
        val region: ClaudePromptRegion,
    )

    private enum class ClaudePromptRegion {
        HISTORY,
        TRANSIENT,
        MEMORY,
        MEMORY_DELTA,
        CLAUDE_MEMORY_DELTA,
        CURRENT,
    }

    private sealed interface ClaudeCacheLocation {
        data class System(val index: Int) : ClaudeCacheLocation
        data class Tool(val index: Int) : ClaudeCacheLocation
        data class Message(val messageIndex: Int, val blockIndex: Int) : ClaudeCacheLocation
    }

    private data class ClaudeRequestLayout(
        val history: List<ClaudeCacheLocation>,
        val transient: List<ClaudeCacheLocation>,
        val memory: List<ClaudeCacheLocation>,
        val memoryDelta: List<ClaudeCacheLocation>,
        val memoryPresent: Boolean,
        val memorySnapshot: List<ClaudeCacheLocation>,
        val system: List<ClaudeCacheLocation>,
        val tools: List<ClaudeCacheLocation>,
    )

    private data class ClaudeCacheBoundaryResult(
        val request: JsonObject,
        val applied: Boolean,
    )

    /** Apply one logical boundary and never mutate any other wire content. */
    private fun applyCacheBoundary(
        request: JsonObject,
        boundary: CacheBoundary,
        layout: ClaudeRequestLayout,
        promptCacheTtl: ClaudePromptCacheTtl,
    ): ClaudeCacheBoundaryResult {
        val candidates = when (boundary) {
            CacheBoundary.SYSTEM_END -> if (layout.system.isNotEmpty()) {
                layout.system.asReversed()
            } else {
                layout.tools.asReversed()
            }

            CacheBoundary.MEMORY_END -> if (layout.memorySnapshot.isNotEmpty()) {
                layout.memorySnapshot.asReversed()
            } else if (layout.memoryPresent) {
                layout.memory.asReversed()
            } else {
                emptyList()
            }

            CacheBoundary.HISTORY_END -> layout.history.asReversed()
        }

        for (candidate in candidates) {
            val updated = request.withCacheControl(candidate, cacheControlEphemeral(promptCacheTtl))
            if (updated != null) return ClaudeCacheBoundaryResult(updated, applied = true)
        }
        return ClaudeCacheBoundaryResult(request, applied = false)
    }

    private fun JsonObject.withCacheControl(
        location: ClaudeCacheLocation,
        cacheControl: JsonObject,
    ): JsonObject? {
        val key: String
        val index: Int
        val block: JsonObject
        when (location) {
            is ClaudeCacheLocation.System -> {
                key = "system"
                index = location.index
                block = this[key]?.jsonArray?.getOrNull(index)?.jsonObject ?: return null
            }

            is ClaudeCacheLocation.Tool -> {
                key = "tools"
                index = location.index
                block = this[key]?.jsonArray?.getOrNull(index)?.jsonObject ?: return null
                return JsonObject(this + (key to JsonArray(
                    this[key]!!.jsonArray.mapIndexed { blockIndex, item ->
                        if (blockIndex == index) JsonObject(block + ("cache_control" to cacheControl)) else item
                    }
                )))
            }

            is ClaudeCacheLocation.Message -> {
                val messages = this["messages"]?.jsonArray ?: return null
                val message = messages.getOrNull(location.messageIndex)?.jsonObject ?: return null
                key = "messages"
                index = location.messageIndex
                block = message["content"]?.jsonArray?.getOrNull(location.blockIndex)?.jsonObject
                    ?: return null
                if (!isCacheControlAllowed(block)) return null
                val content = message["content"]!!.jsonArray
                val updatedMessage = JsonObject(message + ("content" to JsonArray(
                    content.mapIndexed { blockIndex, item ->
                        if (blockIndex == location.blockIndex) {
                            JsonObject(item.jsonObject + ("cache_control" to cacheControl))
                        } else item
                    }
                )))
                return JsonObject(this + (key to JsonArray(
                    messages.mapIndexed { messageIndex, item ->
                        if (messageIndex == index) updatedMessage else item
                    }
                )))
            }
        }

        if (!isCacheControlAllowed(block)) return null
        val array = this[key]?.jsonArray ?: return null
        return JsonObject(this + (key to JsonArray(
            array.mapIndexed { blockIndex, item ->
                if (blockIndex == index) JsonObject(block + ("cache_control" to cacheControl)) else item
            }
        )))
    }

    private fun isCacheControlAllowed(block: JsonObject): Boolean {
        val type = block["type"]?.jsonPrimitive?.contentOrNull
        return when {
            // Thinking and redacted-thinking blocks are deliberately excluded by Anthropic.
            type in setOf("text", "image", "document", "tool_use", "tool_result") -> true
            type == "server_tool_use" -> true
            // Server tools use provider-specific names such as web_fetch_tool_result.
            type?.endsWith("_tool_use") == true || type?.endsWith("_tool_result") == true -> true
            else -> false
        }
    }

    @Suppress("DEPRECATION")
    private fun JsonArrayBuilder.addAssistantMessage(message: UIMessage) {
        // Legacy ToolCall/ToolResult parts can still occur in imported history. They must be
        // rendered as Claude's assistant tool_use followed by a separate user tool_result;
        // putting both blocks in one assistant message changes the timeline and is invalid.
        if (message.parts.any {
                it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult
            }) {
            addLegacyAssistantMessage(message)
            return
        }

        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.toContentBlocks().forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 tool_use 到内容缓冲
                    group.tools.forEach { contentBuffer.add(it.toToolUseBlock()) }

                    // 输出 assistant 消息
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") { contentBuffer.forEach { add(it) } }
                    })
                    contentBuffer.clear()

                    // 紧跟 tool_result
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            group.tools.forEach { add(it.toToolResultBlock()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") { contentBuffer.forEach { add(it) } }
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun JsonArrayBuilder.addLegacyAssistantMessage(message: UIMessage) {
        val assistantContent = mutableListOf<JsonObject>()
        val pendingResults = mutableListOf<JsonObject>()

        fun flushAssistant() {
            if (assistantContent.isEmpty()) return
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") { assistantContent.forEach { add(it) } }
            })
            assistantContent.clear()
        }

        fun flushResults() {
            if (pendingResults.isEmpty()) return
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") { pendingResults.forEach { add(it) } }
            })
            pendingResults.clear()
        }

        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.ToolCall -> {
                    // A new legacy call after results starts another assistant turn.
                    if (pendingResults.isNotEmpty()) {
                        flushAssistant()
                        flushResults()
                    }
                    assistantContent += part.toLegacyToolUseBlock()
                }

                is UIMessagePart.ToolResult -> {
                    flushAssistant()
                    pendingResults += part.toLegacyToolResultBlock()
                }

                is UIMessagePart.Tool -> {
                    if (pendingResults.isNotEmpty()) {
                        flushAssistant()
                        flushResults()
                    }
                    assistantContent += part.toToolUseBlock()
                    if (part.isExecuted) pendingResults += part.toToolResultBlock()
                }

                else -> {
                    // Tool results must immediately follow their assistant tool_use block.
                    flushAssistant()
                    flushResults()
                    assistantContent += part.toContentBlocks()
                }
            }
        }

        flushAssistant()
        flushResults()
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            // Claude has no standalone `tool` role; legacy tool messages are user messages
            // containing tool_result blocks.
            put("role", if (message.role == MessageRole.ASSISTANT) "assistant" else "user")
            putJsonArray("content") {
                message.parts.flatMap { it.toContentBlocks() }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toContentBlocks(): List<JsonObject> = when (this) {
        is UIMessagePart.ServerTool -> serverToolContentBlocks()
        else -> listOfNotNull(toContentBlock())
    }

    /**
     * 解析后 server tool 的 call/result 会合并到同一个 part。回放连续 server tool 时
     * 按原始 content block index 还原顺序；旧消息没有 index 时回退为先 calls、后 results。
     */
    private fun List<UIMessagePart>.toContentBlocks(): List<JsonObject> = buildList {
        val serverTools = mutableListOf<UIMessagePart.ServerTool>()

        fun flushServerTools() {
            val calls = mutableListOf<Pair<JsonObject, Int?>>()
            val results = mutableListOf<Pair<JsonObject, Int?>>()
            serverTools.forEach { tool ->
                val blocks = tool.serverToolContentBlocks()
                val metadata = tool.metadataAs<ServerToolMetadata>()
                blocks.firstOrNull()?.let { calls.add(it to metadata?.callIndex) }
                blocks.getOrNull(1)?.let { results.add(it to metadata?.resultIndex) }
            }
            val blocks = calls + results
            val orderedBlocks = if (blocks.all { it.second != null }) {
                blocks.sortedBy { it.second }
            } else {
                blocks
            }
            orderedBlocks.forEach { add(it.first) }
            serverTools.clear()
        }

        for (part in this@toContentBlocks) {
            if (part is UIMessagePart.ServerTool) {
                serverTools.add(part)
            } else {
                flushServerTools()
                addAll(part.toContentBlocks())
            }
        }
        flushServerTools()
    }

    private fun UIMessagePart.ServerTool.serverToolContentBlocks(): List<JsonObject> {
        val metadata = metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.ANTHROPIC_MESSAGES) return emptyList()

        return buildList {
            val rawCall = metadata?.call.takeIf { protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
            add(rawCall?.let {
                if (input == null) it else JsonObject(it + mapOf("input" to input))
            } ?: buildJsonObject {
                    put("type", "server_tool_use")
                    put("id", toolCallId)
                    put("name", toolName)
                    input?.let { put("input", it) }
                })
            val rawResult = metadata?.result.takeIf { protocol == ServerToolProtocol.ANTHROPIC_MESSAGES }
            if (isFinished && (output != null || rawResult != null)) {
                add(rawResult ?: buildJsonObject {
                    put("type", "${toolName}_tool_result")
                    put("tool_use_id", toolCallId)
                    output?.let { put("content", it) }
                })
            }
        }
    }

    private fun UIMessagePart.toContentBlock(): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        is UIMessagePart.Image -> buildJsonObject {
            encodeBase64(withPrefix = false).onSuccess { encoded ->
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", encoded.mimeType)
                    put("data", encoded.base64)
                })
            }.onFailure {
                Log.w(TAG, "encode image failed: $url", it)
                put("type", "text")
                put("text", "")
            }
        }

        is UIMessagePart.Reasoning -> buildJsonObject {
            put("type", "thinking")
            put("thinking", reasoning)
            metadataAs<ClaudeReasoningMetadata>()?.signature?.let { put("signature", it) }
        }

        is UIMessagePart.Tool -> toToolUseBlock()

        is UIMessagePart.ToolCall -> toLegacyToolUseBlock()

        is UIMessagePart.ToolResult -> toLegacyToolResultBlock()

        else -> null
    }

    @Suppress("DEPRECATION")
    private fun UIMessagePart.ToolCall.toLegacyToolUseBlock() = buildJsonObject {
        put("type", "tool_use")
        put("id", toolCallId)
        put("name", toolName)
        put("input", runCatching { json.parseToJsonElement(arguments) }
            .getOrElse { JsonObject(emptyMap()) })
    }

    @Suppress("DEPRECATION")
    private fun UIMessagePart.ToolResult.toLegacyToolResultBlock() = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", toolCallId)
        put("content", content)
    }

    private fun UIMessagePart.Tool.toToolUseBlock() = buildJsonObject {
        put("type", "tool_use")
        put("id", toolCallId)
        put("name", toolName)
        put("input", inputAsJson())
    }

    private fun UIMessagePart.Tool.toToolResultBlock() = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", toolCallId)
        putJsonArray("content") {
            output.mapNotNull { it.toContentBlock() }.forEach { add(it) }
        }
    }

    internal fun parseMessage(content: JsonArray): UIMessage {
        val parts = mutableListOf<UIMessagePart>()
        val serverToolIndexes = mutableMapOf<String, Int>()

        content.forEachIndexed { blockIndex, contentBlock ->
            val block = contentBlock.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "text", "text_delta" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotEmpty()) {
                        parts.add(UIMessagePart.Text(text))
                    }
                }

                "thinking", "thinking_delta", "signature_delta" -> {
                    val thinking = block["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    val signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    if (thinking.isNotEmpty() || signature != null) {
                        val reasoning = UIMessagePart.Reasoning(
                            reasoning = thinking,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                        if (signature != null) {
                            reasoning.metadata = ClaudeReasoningMetadata(signature = signature).toMetadata()
                        }
                        parts.add(reasoning)
                    }
                }

                "redacted_thinking" -> {
                    val data = block["data"]?.jsonPrimitiveOrNull?.contentOrNull
                    println(data)
                }

                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = id,
                            toolName = name,
                            input = if (input.isEmpty()) "" else json.encodeToString(input),
                            output = emptyList()
                        )
                    )
                }

                else -> if (type.isClaudeServerToolUseType()) {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    serverToolIndexes[id] = parts.size
                    parts.add(
                        UIMessagePart.ServerTool(
                            toolCallId = id,
                            toolName = block["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            input = block["input"],
                            status = ServerToolStatus.IN_PROGRESS,
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                call = block,
                                callIndex = blockIndex,
                            ).toMetadata(),
                        )
                    )
                } else if (type == "input_json_delta") {
                    val input = block["partial_json"]?.jsonPrimitive?.contentOrNull
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = "",
                            toolName = "",
                            input = input ?: "",
                            output = emptyList()
                        )
                    )
                } else if (type.isClaudeServerToolResultType()) {
                    val id = block["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val output = block["content"]
                    val status = if (output.isClaudeServerToolError()) {
                        ServerToolStatus.FAILED
                    } else {
                        ServerToolStatus.COMPLETED
                    }
                    val index = serverToolIndexes[id]
                    if (index == null || parts.getOrNull(index) !is UIMessagePart.ServerTool) {
                        parts.add(UIMessagePart.ServerTool(
                            toolCallId = id,
                            toolName = type?.removeSuffix("_tool_result") ?: "",
                            output = output,
                            status = status,
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                result = block,
                                resultIndex = blockIndex,
                            ).toMetadata(),
                        ))
                    } else {
                        val tool = parts[index] as UIMessagePart.ServerTool
                        parts[index] = tool.copy(
                            output = output,
                            status = status,
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                call = tool.metadataAs<ServerToolMetadata>()?.call,
                                callIndex = tool.metadataAs<ServerToolMetadata>()?.callIndex,
                                result = block,
                                resultIndex = blockIndex,
                            ).toMetadata(),
                        )
                    }
                }
            }
        }

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts
        )
    }

    private fun parseTokenUsage(bodyJson: JsonObject?): TokenUsage? {
        if (bodyJson == null) return null

        // 回退到标准 usage 字段
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + (cachedInputTokens ?: 0) + (cachedCreationTokens ?: 0)
        return UsageNormalizer.normalize(
            totalInputTokens = promptTokens,
            completionTokens = completionTokens,
            cacheReadTokens = cachedInputTokens,
            cacheWriteTokens = cachedCreationTokens,
            provider = "claude",
            model = bodyJson["model"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

internal fun String?.isClaudeServerToolResultType(): Boolean =
    this != null && this != "tool_result" && endsWith("_tool_result")

internal fun String?.isClaudeServerToolUseType(): Boolean =
    this == "server_tool_use" || (this != null && this != "tool_use" && endsWith("_tool_use"))

internal fun JsonElement?.isClaudeServerToolError(): Boolean {
    val content = this as? JsonObject ?: return false
    return content["type"]?.jsonPrimitive?.contentOrNull?.endsWith("_error") == true
}
