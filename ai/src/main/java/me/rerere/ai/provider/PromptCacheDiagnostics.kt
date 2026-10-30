package me.rerere.ai.provider

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
enum class PromptCacheApi { CLAUDE, CHAT_COMPLETIONS, RESPONSES, GEMINI }

@Serializable
enum class PromptCacheSection { TOOLS, SYSTEM, MEMORY, HISTORY, TRANSIENT, MEMORY_DELTA, CURRENT, CUSTOM_PROMPT }

@Serializable
enum class PromptCacheChange { APPENDED, CHANGED }

/** Only observed states are persisted, never request content or its fingerprints. */
@Serializable
data class PromptCacheDiagnostic(
    val api: PromptCacheApi,
    val baseline: Boolean,
    val changes: Map<PromptCacheSection, PromptCacheChange> = emptyMap(),
    val appliedBoundaries: List<CacheBoundary> = emptyList(),
    val layoutSkipped: Boolean = false,
    val cacheKeySent: Boolean = false,
)

/** Process-local keyed fingerprints cannot be tested against guessed text after log export. */
data class PromptCacheSnapshot(
    val api: PromptCacheApi,
    val sections: Map<PromptCacheSection, List<String>>,
    val appliedBoundaries: List<CacheBoundary>,
    val layoutSkipped: Boolean,
    val cacheKeySent: Boolean,
)

class PromptCacheTracker(private val maxEntries: Int = 64) {
    init { require(maxEntries > 0) }

    private val previous = object : LinkedHashMap<String, PromptCacheSnapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PromptCacheSnapshot>) = size > maxEntries
    }

    @Synchronized
    fun observe(scope: String, snapshot: PromptCacheSnapshot): PromptCacheDiagnostic {
        val old = previous.put(scope, snapshot)
        val changes = if (old == null) emptyMap() else snapshot.sections.mapNotNull { (section, hashes) ->
            val before = old.sections[section].orEmpty()
            when {
                hashes == before -> null
                hashes.size > before.size && hashes.take(before.size) == before -> section to PromptCacheChange.APPENDED
                else -> section to PromptCacheChange.CHANGED
            }
        }.toMap()
        return PromptCacheDiagnostic(
            api = snapshot.api,
            baseline = old == null,
            changes = changes,
            appliedBoundaries = snapshot.appliedBoundaries,
            layoutSkipped = snapshot.layoutSkipped,
            cacheKeySent = snapshot.cacheKeySent,
        )
    }

    @Synchronized
    fun clear() = previous.clear()
}

private object PromptFingerprints {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "HmacSHA256")
    fun of(value: JsonElement): String = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal(value.toString().toByteArray(Charsets.UTF_8)).promptCacheHex()
    }
}

/** Called after custom bodies and cache markers, without modifying the wire request. */
internal fun JsonObject.observePromptCacheRequest(
    params: TextGenerationParams,
    api: PromptCacheApi,
    appliedBoundaries: List<CacheBoundary> = emptyList(),
    layoutSkipped: Boolean = false,
    logicalPrompt: LogicalPrompt? = params.logicalPrompt,
): JsonObject {
    val observer = params.onPromptCacheSnapshot ?: return this
    runCatching { observer(promptCacheSnapshot(params, api, appliedBoundaries, layoutSkipped, logicalPrompt)) }
    return this
}

private fun JsonObject.promptCacheSnapshot(
    params: TextGenerationParams, api: PromptCacheApi,
    appliedBoundaries: List<CacheBoundary>, layoutSkipped: Boolean,
    logicalPrompt: LogicalPrompt?,
): PromptCacheSnapshot {
    val promptKeys = setOf("messages", "system", "tools", "instructions", "input", "contents", "systemInstruction")
    val customLayout = params.customBody.any { it.key in promptKeys }
    val prompt = logicalPrompt
    fun messages(values: List<UIMessage>): List<String> {
        val hashes = values.map { message ->
            val parts = Json.encodeToJsonElement(ListSerializer(UIMessagePart.serializer()), message.parts).jsonArray
            PromptFingerprints.of(buildJsonObject {
                put("role", message.role.name)
                put("parts", JsonArray(parts.map { part ->
                    JsonObject(part.jsonObject.filterKeys { it !in setOf("createdAt", "finishedAt", "approvalState") })
                }))
            })
        }
        // Cap retained state for long conversations. A compacted tail is reported as changed.
        return if (hashes.size <= 512) hashes else hashes.take(511) +
            PromptFingerprints.of(JsonArray(hashes.drop(511).map(::JsonPrimitive)))
    }
    val sections = if (customLayout || prompt == null) {
        mapOf(PromptCacheSection.CUSTOM_PROMPT to listOf(PromptFingerprints.of(JsonObject(filterKeys { it in promptKeys }))))
    } else {
        val memory = if (api == PromptCacheApi.CLAUDE && prompt.claudeMemoryCacheBlocks != null) {
            prompt.claudeMemoryCacheBlocks.map { PromptFingerprints.of(JsonPrimitive(it.text)) }
        } else {
            @Suppress("DEPRECATION")
            messages(prompt.memorySnapshot.ifEmpty { prompt.memory })
        }
        mapOf(
            PromptCacheSection.TOOLS to listOf(PromptFingerprints.of(this["tools"] ?: JsonNull)),
            PromptCacheSection.SYSTEM to messages(prompt.system),
            PromptCacheSection.MEMORY to memory,
            PromptCacheSection.HISTORY to messages(prompt.history),
            PromptCacheSection.TRANSIENT to messages(prompt.transientContext),
            PromptCacheSection.MEMORY_DELTA to messages(prompt.memoryDelta + if (api == PromptCacheApi.CLAUDE) prompt.claudeMemoryDelta else emptyList()),
            PromptCacheSection.CURRENT to messages(listOfNotNull(prompt.latestUser) + prompt.currentToolLoop),
        )
    }
    return PromptCacheSnapshot(
        api = api,
        sections = sections,
        appliedBoundaries = appliedBoundaries,
        layoutSkipped = layoutSkipped,
        cacheKeySent = listOf("prompt_cache_key", "promptCacheKey").any {
            (this[it] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
        },
    )
}
