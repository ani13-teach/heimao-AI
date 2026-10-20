package me.rerere.rikkahub.data.ai.tools

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository

/** Includes JSON escaping and all envelope/continuation fields, measured in UTF-16 characters. */
internal const val LAYERED_MEMORY_OUTPUT_LIMIT = 16_000
private const val METADATA_TEXT_LIMIT = 160
private const val METADATA_LIST_LIMIT = 8
private const val METADATA_LIST_TEXT_LIMIT = 48

internal fun memoryDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun String.codePointsLength(): Int = codePointCount(0, length)
internal fun String.codePointSlice(start: Int, end: Int): String =
    substring(offsetByCodePoints(0, start), offsetByCodePoints(0, end))

internal fun boundedMemorySummary(memory: AssistantMemory): JsonObject {
    val truncated = mutableListOf<String>()
    fun text(field: String, value: String): String {
        val length = value.codePointsLength()
        if (length > METADATA_TEXT_LIMIT) truncated += field
        return value.codePointSlice(0, minOf(length, METADATA_TEXT_LIMIT))
    }
    fun strings(field: String, values: List<String>): JsonArray {
        if (values.size > METADATA_LIST_LIMIT) truncated += field
        return JsonArray(values.take(METADATA_LIST_LIMIT).map {
            val size = it.codePointsLength()
            if (size > METADATA_LIST_TEXT_LIMIT) truncated += field
            JsonPrimitive(it.codePointSlice(0, minOf(size, METADATA_LIST_TEXT_LIMIT)))
        })
    }
    return buildJsonObject {
        put("id", memory.id)
        put("category", text("category", memory.category))
        put("title", text("title", memory.title))
        put("updated", text("updated", memory.updated))
        put("confidence_flags", strings("confidence_flags", memory.confidenceFlags))
        put("tags", strings("tags", memory.tags))
        put("product", text("product", memory.product))
        put("status", memory.status)
        put("metadata_truncated", truncated.isNotEmpty())
        put("truncated_fields", JsonArray(truncated.distinct().map(::JsonPrimitive)))
    }
}

/** Binary search on code points; the predicate measures the final serialized envelope. */
internal fun fitMemoryText(text: String, start: Int = 0, fits: (String, Int) -> Boolean): Pair<String, Int> {
    val total = text.codePointsLength()
    require(start in 0..total) { "offset is outside the original text" }
    var low = start
    var high = total
    while (low < high) {
        val mid = low + (high - low + 1) / 2
        if (fits(text.codePointSlice(start, mid), mid)) low = mid else high = mid - 1
    }
    return text.codePointSlice(start, low) to low
}

internal fun checkedMemoryOutput(payload: JsonObject): String = payload.toString().also {
    check(it.length <= LAYERED_MEMORY_OUTPUT_LIMIT) { "memory output budget exceeded" }
}

/** The generation error path uses the same serialized budget as successful tool results. */
internal fun boundedLayeredMemoryError(error: Throwable): String {
    val message = error.message.orEmpty()
    fun envelope(text: String) = buildJsonObject {
        put("success", false)
        put("error_type", error.javaClass.simpleName.take(160))
        put("error", text)
        put("error_truncated", text != message)
    }
    val (fragment, _) = fitMemoryText(message) { text, _ ->
        envelope(text).toString().length <= LAYERED_MEMORY_OUTPUT_LIMIT
    }
    return checkedMemoryOutput(envelope(fragment))
}

internal data class MemoryReadQuery(
    val id: Int?, val keywords: String?, val category: String?, val confidence: String?,
    val status: String, val limit: Int, val cursor: String?, val offset: Int, val version: String?,
)

internal fun parseMemoryReadQuery(params: JsonObject): MemoryReadQuery {
    fun string(key: String): String? = params[key]?.let {
        require(it is JsonPrimitive && it.isString) { "$key must be a string" }
        it.content
    }
    fun integer(key: String, default: Int? = null): Int? = params[key]?.let {
        require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "$key must be an integer" }
        it.intOrNull
    } ?: default
    val id = integer("id")
    val keywords = string("keywords")
    val category = string("category")
    val confidence = string("confidence")
    val status = string("status") ?: "active"
    val cursor = string("cursor")
    val version = string("version")
    val offset = integer("offset", 0)!!
    val limit = integer("limit", 5)!!
    require(status in listOf("active", "archived")) { "status must be active or archived" }
    require(category == null || category.isBlank() || category in listOf("PARAMS", "SOP", "METHOD", "CASES", "ACTIVE", "GENERAL")) { "invalid category" }
    require(limit in 1..20) { "limit must be between 1 and 20" }
    require(offset >= 0) { "offset must not be negative" }
    if (id != null) {
        require(listOf("keywords", "category", "confidence", "cursor").none(params::containsKey)) {
            "id cannot be combined with keywords, category, confidence or cursor"
        }
    } else {
        require(!params.containsKey("offset") && version == null) { "offset/version require id" }
    }
    require(!params.containsKey("offset") || version != null) { "offset requires version; use the returned position" }
    return MemoryReadQuery(id, keywords, category, confidence, status, limit, cursor, offset, version)
}

private fun readBinding(scope: String, query: MemoryReadQuery, action: String): String = memoryDigest(
    buildJsonArray {
        add(scope); add(action); add(query.keywords); add(query.category); add(query.confidence); add(query.status)
    }.toString()
)

private data class MemoryPageKey(val updated: String?, val id: Int, val updatedHash: String? = null)
private fun encodeMemoryCursor(binding: String, memory: AssistantMemory): String = Base64.getUrlEncoder()
    .withoutPadding().encodeToString(buildJsonObject {
        put("query", binding)
        if (memory.updated.length <= 128) put("updated", memory.updated)
        else put("updated_hash", memoryDigest(memory.updated))
        put("id", memory.id)
    }.toString().toByteArray(Charsets.UTF_8))

private fun decodeMemoryCursor(cursor: String, binding: String): MemoryPageKey {
    require(cursor.length <= 8192) { "invalid cursor; restart from the first page" }
    return try {
        val obj = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)).jsonObject
        require(obj["query"]?.jsonPrimitive?.content == binding) { "cursor scope or filters changed; restart from the first page" }
        val updated = obj["updated"]?.jsonPrimitive?.content
        val hash = obj["updated_hash"]?.jsonPrimitive?.content
        require((updated == null) != (hash == null)) { "cursor sort key is invalid" }
        MemoryPageKey(updated, obj.getValue("id").jsonPrimitive.int, hash)
    } catch (e: Exception) {
        throw IllegalArgumentException("invalid cursor or changed scope/filters; restart from the first page", e)
    }
}

internal suspend fun readLayeredMemory(scope: String, repo: MemoryRepository, params: JsonObject, directory: Boolean = false): JsonObject {
    val query = parseMemoryReadQuery(params)
    if (query.id != null) {
        val memory = repo.getMemoryInScope(scope, query.id, query.status) ?: return buildJsonObject {
            put("result", "not_found"); put("count", 0); put("has_more", false); put("memories", JsonArray(emptyList()))
        }
        val version = memoryDigest(memory.content)
        if (query.version != null && query.version != version) return buildJsonObject {
            put("result", "version_changed"); put("id", memory.id); put("status", memory.status)
            put("version", version); put("restart_required", true)
        }
        require(query.offset <= memory.content.codePointsLength()) { "offset is outside the original text" }
        fun envelope(record: JsonObject) = buildJsonObject {
            put("result", "ok"); put("count", 1); put("has_more", false); put("memories", JsonArray(listOf(record)))
        }
        val record = memoryRecordFragment(memory, query.offset) { envelope(it).toString().length <= LAYERED_MEMORY_OUTPUT_LIMIT }
        return envelope(record)
    }
    val action = if (directory) "rebuild_index" else "memory_read"
    val binding = readBinding(scope, query, action)
    val cursorKey = query.cursor?.let { decodeMemoryCursor(it, binding) }
    // Legacy data can contain arbitrary updated strings. Keep cursors bounded without losing the SQL sort key.
    val after = if (cursorKey?.updatedHash != null) {
        val anchor = repo.getMemoryInScope(scope, cursorKey.id, query.status)
        require(anchor != null && memoryDigest(anchor.updated) == cursorKey.updatedHash) {
            "cursor anchor changed; restart from the first page"
        }
        MemoryPageKey(anchor.updated, anchor.id)
    } else cursorKey
    val rows = repo.searchMemoriesPage(scope, query.keywords, query.category, query.confidence, query.status,
        query.limit + 1, after?.updated, after?.id)
    val records = mutableListOf<JsonObject>()
    var last: AssistantMemory? = null
    fun envelope(items: List<JsonObject>, lastItem: AssistantMemory?, more: Boolean): JsonObject = buildJsonObject {
        put("result", if (items.isEmpty()) "no_matches" else "ok")
        if (directory) put("action", action)
        put("count", items.size); put("memories", JsonArray(items)); put("has_more", more)
        put("complete", !more)
        if (more && lastItem != null) put("next_cursor", encodeMemoryCursor(binding, lastItem))
    }
    for (memory in rows.take(query.limit)) {
        // Reserve the real next cursor before adding any content, including budget-driven deferrals.
        val record = if (directory) boundedMemorySummary(memory) else memoryRecordFragment(memory, 0) {
            envelope(records + it, memory, true).toString().length <= LAYERED_MEMORY_OUTPUT_LIMIT
        }
        if (envelope(records + record, memory, true).toString().length > LAYERED_MEMORY_OUTPUT_LIMIT) break
        if (!directory && memory.content.isNotEmpty() && record["end"]?.jsonPrimitive?.int == 0 && records.isNotEmpty()) break
        records += record
        last = memory
    }
    check(rows.isEmpty() || records.isNotEmpty()) { "record metadata exceeds output budget" }
    return envelope(records, last, rows.size > records.size)
}

private fun memoryRecordFragment(memory: AssistantMemory, start: Int, fits: (JsonObject) -> Boolean): JsonObject {
    val total = memory.content.codePointsLength()
    val version = memoryDigest(memory.content)
    val summary = boundedMemorySummary(memory)
    fun record(content: String, end: Int) = JsonObject(summary + buildJsonObject {
        put("content", content); put("version", version); put("start", start); put("end", end); put("total_length", total)
        put("content_complete", end == total)
        if (end < total) put("next_offset", end)
    })
    val (content, end) = fitMemoryText(memory.content, start) { text, position -> fits(record(text, position)) }
    return record(content, end)
}

internal fun boundMemoryWriteReceipt(payload: JsonObject): JsonObject {
    val fragment = payload["fragment"]?.jsonPrimitive?.content ?: return payload
    fun receipt(text: String) = JsonObject(payload + buildJsonObject {
        put("fragment", text)
        put("fragment_truncated", payload["fragment_truncated"]?.jsonPrimitive?.boolean == true || text != fragment)
    })
    val (text, _) = fitMemoryText(fragment) { value, _ -> receipt(value).toString().length <= LAYERED_MEMORY_OUTPUT_LIMIT }
    return receipt(text)
}

/** Report cursors are session-bound; archive pages only ever read an immutable receipt. */
internal class LayeredMemoryReports {
    private data class Snapshot(val raw: String, val payload: JsonObject)
    private data class Position(val binding: String, val version: String, val snapshot: String?, val offset: Int)
    private val snapshots = LinkedHashMap<String, Snapshot>()
    private val positions = LinkedHashMap<String, Position>()

    @Synchronized
    fun start(scope: String, request: JsonObject, payload: JsonObject): JsonObject {
        val raw = payload.toString()
        val key = if (request["action"]?.jsonPrimitive?.content == "archive") UUID.randomUUID().toString() else null
        if (key != null) {
            // Archive accepts at most 10000 integer IDs, so each snapshot is bounded independently.
            while (snapshots.size >= 4) {
                val oldest = snapshots.keys.first()
                snapshots.remove(oldest)
                positions.entries.removeAll { it.value.snapshot == oldest }
            }
            snapshots[key] = Snapshot(raw, payload)
        }
        return page(Position(binding(scope, request), memoryDigest(raw), key, 0), payload, raw)
    }

    @Synchronized
    fun resume(scope: String, request: JsonObject, cursor: String, current: JsonObject? = null): JsonObject {
        val position = positions[cursor] ?: error("report cursor expired; restart the query (archive is not repeated automatically)")
        require(position.binding == binding(scope, request)) { "report cursor scope/action/parameters changed; restart the query" }
        val snapshot = position.snapshot?.let { snapshots[it] ?: error("archive receipt expired; operation was not repeated") }
        val payload = snapshot?.payload ?: requireNotNull(current) { "current report is required" }
        val raw = snapshot?.raw ?: payload.toString()
        require(memoryDigest(raw) == position.version) { "report version changed; restart from the first page" }
        return page(position, payload, raw)
    }

    private fun binding(scope: String, request: JsonObject): String = memoryDigest(buildJsonObject {
        put("scope", scope)
        put("request", JsonObject(request.filterKeys { it != "cursor" }.toSortedMap()))
    }.toString())

    private fun page(position: Position, payload: JsonObject, raw: String): JsonObject {
        val start = position.offset
        val total = raw.codePointsLength()
        val token = UUID.randomUUID().toString()
        fun envelope(fragment: String, end: Int) = buildJsonObject {
            put("action", payload.getValue("action"))
            put("report_version", position.version)
            payload["success"]?.let { put("success", it) }
            payload["dry_run"]?.let { put("dry_run", it) }
            put("report_fragment", fragment); put("start", start); put("end", end); put("total_length", total)
            put("complete", end == total); put("has_more", end < total)
            if (end < total) put("next_cursor", token)
        }
        val full = JsonObject(payload + buildJsonObject {
            put("report_version", position.version); put("complete", true); put("has_more", false)
        })
        if (start == 0 && full.toString().length <= LAYERED_MEMORY_OUTPUT_LIMIT) return full
        val (fragment, end) = fitMemoryText(raw, start) { text, position -> envelope(text, position).toString().length <= LAYERED_MEMORY_OUTPUT_LIMIT }
        check(end > start || start == total) { "report envelope exceeds budget" }
        if (end < total) {
            while (positions.size >= 128) positions.remove(positions.keys.first())
            positions[token] = position.copy(offset = end)
        }
        return envelope(fragment, end)
    }
}
