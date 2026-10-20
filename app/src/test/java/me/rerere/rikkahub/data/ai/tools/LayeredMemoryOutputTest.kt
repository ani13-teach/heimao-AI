package me.rerere.rikkahub.data.ai.tools

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.*
import org.junit.Test

class LayeredMemoryOutputTest {
    private val json = Json

    /** Proxy keeps this fake independent of unrelated new DAO methods. Unexpected access fails. */
    private class Store {
        val rows = mutableListOf<MemoryEntity>()
        var updates = 0
        var preloadCalls = 0
        @Suppress("UNCHECKED_CAST")
        val dao: MemoryDAO = Proxy.newProxyInstance(MemoryDAO::class.java.classLoader, arrayOf(MemoryDAO::class.java)) { _, method, values ->
            val args = values.orEmpty()
            when {
                method.name == "getMemoryById" -> rows.firstOrNull { it.id == args[0] }
                method.name.startsWith("getMemory") && args.size == 4 && args.take(3).count { it is Int } == 1 -> {
                    val id = args.take(3).filterIsInstance<Int>().single()
                    val strings = args.take(3).filterIsInstance<String>()
                    val status = strings.single { it in setOf("active", "archived") }
                    val scope = strings.single { it !in setOf("active", "archived") }
                    rows.firstOrNull { it.assistantId == scope && it.id == id && it.status == status }
                }
                method.name == "searchMemoriesPage" -> {
                    val scope = args[0] as String
                    val keyword = args[1] as String
                    val category = args[2] as String?
                    val confidence = (args[3] as String?)?.removePrefix("%")?.removeSuffix("%")
                    val status = args[4] as String
                    val limit = args[5] as Int
                    val afterUpdated = args[6] as String?
                    val afterId = args[7] as Int?
                    rows.filter { row ->
                        row.assistantId == scope && row.status == status &&
                            (category == null || row.category == category) &&
                            (confidence == null || row.confidenceFlags.contains(confidence)) &&
                            (keyword.isEmpty() || row.content.contains(keyword) || row.title.contains(keyword) || row.tags.contains(keyword)) &&
                            (afterUpdated == null || row.updated < afterUpdated || row.updated == afterUpdated && row.id < afterId!!)
                    }.sortedWith(compareByDescending<MemoryEntity> { it.updated }.thenByDescending { it.id }).take(limit)
                }
                method.name == "getMemoriesByStatus" -> rows.filter { it.assistantId == args[0] && it.status == args[1] }
                method.name == "updateMemory" -> {
                    val row = args[0] as MemoryEntity
                    rows[rows.indexOfFirst { it.id == row.id }] = row
                    updates++
                    Unit
                }
                method.name in setOf("getActiveMemoriesForPrompt", "getMemoriesForLayeredIndex") -> {
                    preloadCalls++
                    error("automatic context must not be loaded")
                }
                else -> error("unexpected DAO method ${method.name}")
            }
        } as MemoryDAO
        val repo = MemoryRepository(dao)
        fun add(id: Int, body: String = "正文$id", scope: String = "a", status: String = "active", title: String = "标题$id", tags: String = "[]") {
            rows += MemoryEntity(id = id, assistantId = scope, content = body, title = title, status = status, updated = "2026-09-10", tags = tags)
        }
    }

    private fun call(tool: Tool, request: JsonObject): UIMessagePart.Text = runBlocking {
        tool.execute(request).filterIsInstance<UIMessagePart.Text>().single().also {
            assertTrue("serialized output: ${it.text.length}", it.text.length <= LAYERED_MEMORY_OUTPUT_LIMIT)
            Json.parseToJsonElement(it.text).jsonObject
        }
    }
    private fun read(store: Store, request: JsonObject, scope: String = "a"): JsonObject = runBlocking {
        readLayeredMemory(scope, store.repo, request).also { checkedMemoryOutput(it) }
    }
    private fun request(block: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject(block)
    private fun JsonObject.record(): JsonObject = getValue("memories").jsonArray.single().jsonObject
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    @Test fun `exact read enforces scope status and exclusive arguments`() {
        val store = Store().apply { add(1); add(2, scope = "b"); add(3, status = "archived") }
        assertEquals("no_matches", read(store, request { put("keywords", "missing") }).text("result"))
        assertEquals("not_found", read(store, request { put("id", 2) }).text("result"))
        assertEquals("not_found", read(store, request { put("id", 3) }).text("result"))
        assertEquals(3, read(store, request { put("id", 3); put("status", "archived") }).record().int("id"))
        listOf("keywords", "category", "confidence", "cursor").forEach { field ->
            assertThrows(IllegalArgumentException::class.java) {
                parseMemoryReadQuery(request { put("id", 1); put(field, "") })
            }
        }
        listOf(request { put("offset", 0) }, request { put("id", 1); put("offset", 0) }, request { put("id", 1); put("offset", -1); put("version", "v") }).forEach {
            assertThrows(IllegalArgumentException::class.java) { parseMemoryReadQuery(it) }
        }
    }

    @Test fun `long escaped Unicode body reconstructs exactly and detects same-day edits`() {
        val body = "中文\r\n😀\"\\\t".repeat(5000)
        val store = Store().apply { add(1, body) }
        var record = read(store, request { put("id", 1) }).record()
        val version = record.text("version")
        val rebuilt = StringBuilder(record.text("content"))
        while (!record.flag("content_complete")) {
            val offset = record.int("next_offset")
            assertTrue(offset > record.int("start"))
            record = read(store, request { put("id", 1); put("version", version); put("offset", offset) }).record()
            assertEquals(offset, record.int("start"))
            rebuilt.append(record.text("content"))
        }
        assertEquals(body, rebuilt.toString())
        assertEquals(body.codePointCount(0, body.length), record.int("total_length"))
        val end = read(store, request { put("id", 1); put("version", version); put("offset", record.int("total_length")) }).record()
        assertTrue(end.flag("content_complete")); assertEquals("", end.text("content"))
        assertThrows(IllegalArgumentException::class.java) {
            read(store, request { put("id", 1); put("version", version); put("offset", record.int("total_length") + 1) })
        }
        store.rows[0] = store.rows[0].copy(content = body + "新")
        assertEquals("version_changed", read(store, request { put("id", 1); put("version", version); put("offset", 1) }).text("result"))
    }

    @Test fun `keyset pages use last actually returned row even under escaped metadata budget`() {
        val tags = JsonArray(List(100) { JsonPrimitive("\u0001😀".repeat(1000)) }).toString()
        val store = Store().apply {
            (1..9).forEach { add(it, body = "\u0001😀\n".repeat(9000), title = "\u0001".repeat(9000), tags = tags) }
        }
        val ids = mutableListOf<Int>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = read(store, request { put("limit", 3); cursor?.let { put("cursor", it) } })
            val records = page.getValue("memories").jsonArray.map { it.jsonObject }
            assertTrue(records.isNotEmpty())
            records.forEach {
                ids += it.int("id")
                assertTrue(it.flag("metadata_truncated"))
                assertTrue(it.int("end") > 0)
                assertTrue(!it.flag("content_complete"))
            }
            cursor = page["next_cursor"]?.jsonPrimitive?.content
            assertEquals(cursor != null, page.flag("has_more"))
            pages++
            assertTrue(pages <= 9)
        } while (cursor != null)
        assertEquals((9 downTo 1).toList(), ids)
        assertTrue(pages > 3)
    }

    @Test fun `list cursor cannot cross filters scope status or directory action`() {
        val store = Store().apply { add(1); add(2); add(3) }
        val cursor = read(store, request { put("limit", 1) }).text("next_cursor")
        listOf(request { put("cursor", cursor); put("keywords", "正文") }, request { put("cursor", cursor); put("status", "archived") }).forEach {
            assertThrows(IllegalArgumentException::class.java) { read(store, it) }
        }
        assertThrows(IllegalArgumentException::class.java) { read(store, request { put("cursor", cursor) }, scope = "b") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { readLayeredMemory("a", store.repo, request { put("cursor", cursor) }, directory = true) } }
        val directory = buildLayeredMemoryTools(json, "a", store.repo).single { it.name == "memory_consolidate" }
        val page = Json.parseToJsonElement(call(directory, request { put("action", "rebuild_index"); put("limit", 1) }).text).jsonObject
        assertTrue(page.flag("has_more")); assertEquals(0, store.preloadCalls)
        assertFalse(page.getValue("memories").jsonArray.first().jsonObject.containsKey("content"))
    }

    @Test fun `legacy long sort metadata cannot inflate the continuation cursor`() {
        val store = Store().apply { add(1); add(2) }
        store.rows.replaceAll { it.copy(updated = "z".repeat(10000)) }
        val first = read(store, request { put("limit", 1) })
        assertTrue(first.text("next_cursor").length < 512)
        assertTrue(first.record().flag("metadata_truncated"))
        val second = read(store, request { put("cursor", first.text("next_cursor")); put("limit", 1) })
        assertEquals(1, second.record().int("id"))
        assertFalse(second.flag("has_more"))
    }

    @Test fun `report fragments retain every huge field and bind content version`() {
        val reports = LayeredMemoryReports()
        val req = request { put("action", "detect_conflicts") }
        val report = request { put("action", "detect_conflicts"); put("key", "\u0001😀\"\n".repeat(12000)); put("id_a", 1); put("id_b", 2) }
        var page = reports.start("a", req, report)
        val originalCursor = page.text("next_cursor")
        val rebuilt = StringBuilder()
        do {
            checkedMemoryOutput(page)
            rebuilt.append(page.text("report_fragment"))
            page = if (page.flag("has_more")) reports.resume("a", req, page.text("next_cursor"), report) else break
        } while (true)
        assertEquals(report, Json.parseToJsonElement(rebuilt.toString()))
        assertThrows(IllegalArgumentException::class.java) { reports.resume("b", req, originalCursor, report) }
        assertThrows(IllegalArgumentException::class.java) { reports.resume("a", request { put("action", "suggest_upgrades") }, originalCursor, report) }
        assertThrows(IllegalArgumentException::class.java) { reports.resume("a", req, originalCursor, JsonObject(report + ("new" to JsonPrimitive(1)))) }
        assertThrows(IllegalStateException::class.java) { LayeredMemoryReports().resume("a", req, originalCursor, report) }
    }

    @Test fun `archive receipt continuation does not repeat database writes`() {
        val store = Store().apply { (1..10000).forEach { add(it) } }
        val tool = buildLayeredMemoryTools(json, "a", store.repo).single { it.name == "memory_consolidate" }
        val req = request { put("action", "archive"); put("dry_run", false); put("ids", JsonArray((1..10000).map(::JsonPrimitive))) }
        var page = Json.parseToJsonElement(call(tool, req).text).jsonObject
        assertTrue(page.flag("success")); assertTrue(page.flag("has_more"))
        val cursor = page.text("next_cursor")
        val updates = store.updates
        val rebuilt = StringBuilder()
        do {
            rebuilt.append(page.text("report_fragment"))
            page = if (page.flag("has_more")) Json.parseToJsonElement(call(tool, JsonObject(req + ("cursor" to JsonPrimitive(page.text("next_cursor"))))).text).jsonObject else break
        } while (true)
        assertEquals(updates, store.updates)
        assertEquals(10000, Json.parseToJsonElement(rebuilt.toString()).jsonObject.getValue("archived").jsonArray.size)
        val rebuiltTool = buildLayeredMemoryTools(json, "a", store.repo).single { it.name == "memory_consolidate" }
        assertThrows(IllegalStateException::class.java) { call(rebuiltTool, JsonObject(req + ("cursor" to JsonPrimitive(cursor)))) }
        assertEquals(updates, store.updates)
    }

    @Test fun `write receipt fits final serialization while full diff metadata survives`() {
        val store = Store().apply { add(1, body = "旧正文", title = "\u0001".repeat(10000)) }
        val tool = buildLayeredMemoryTools(json, "a", store.repo).single { it.name == "memory_write" }
        val body = "新正文😀\n".repeat(10000)
        val part = call(tool, request { put("action", "replace"); put("id", 1); put("content", body); put("tags", JsonArray(List(50) { JsonPrimitive("\u0001".repeat(1000)) })) })
        val result = Json.parseToJsonElement(part.text).jsonObject
        assertTrue(result.flag("success")); assertTrue(result.flag("fragment_truncated"))
        assertTrue(result.getValue("memory").jsonObject.flag("metadata_truncated"))
        assertTrue(requireNotNull(part.metadataAs<DiffMetadata>()?.diff).length > LAYERED_MEMORY_OUTPUT_LIMIT)
        assertEquals(body.trim(), store.rows.single().content)
    }

    @Test fun `metadata budget includes escaped arrays and preserves supplementary characters`() {
        val source = AssistantMemory(1, title = "😀".repeat(1000), tags = List(100) { "\u0001".repeat(1000) }, confidenceFlags = List(100) { "\u0001".repeat(1000) })
        val summary = boundedMemorySummary(source)
        assertTrue(summary.flag("metadata_truncated"))
        assertEquals(160, summary.text("title").codePointsLength())
        assertTrue(summary.toString().length < LAYERED_MEMORY_OUTPUT_LIMIT)
        val receipt = boundMemoryWriteReceipt(request { put("success", true); put("memory", summary); put("fragment", "\u0001".repeat(1200)) })
        checkedMemoryOutput(receipt)
    }
}
