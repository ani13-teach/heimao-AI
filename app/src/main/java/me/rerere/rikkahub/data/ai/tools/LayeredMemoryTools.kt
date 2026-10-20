package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryEdit
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.detectConflicts
import me.rerere.rikkahub.data.repository.suggestConfidenceUpgrades
import me.rerere.rikkahub.utils.generateUnifiedDiff

private val MEMORY_CATEGORIES = listOf("PARAMS", "SOP", "METHOD", "CASES", "ACTIVE", "GENERAL")

/** 一次调用最多携带的局部修改点，避免单次输出与写入无界。 */
private const val MAX_EDITS_PER_CALL = 20

/** 返回给模型的 diff 片段长度上限，防止“局部修改”又把大段正文塞回上下文。 */
private const val RESULT_FRAGMENT_CHAR_LIMIT = 1200

/**
 * memory_write 的解析结果。修改（Update）时正文只有两条互斥路径：
 * [MemoryWriteCommand.Update.edits] 局部修改，或 [MemoryWriteCommand.Update.content] 整段重写。
 */
internal sealed interface MemoryWriteCommand {
    data class Create(
        val category: String,
        val title: String,
        val content: String,
        val confidenceFlags: List<String>,
        val tags: List<String>,
        val product: String?,
    ) : MemoryWriteCommand

    data class Update(
        val id: Int,
        val content: String?,
        val edits: List<MemoryEdit>,
        val category: String?,
        val title: String?,
        val confidenceFlags: List<String>?,
        val tags: List<String>?,
        val product: String?,
    ) : MemoryWriteCommand {
        /** 回给模型的模式标签：整段重写还是局部修改。 */
        val mode: String get() = if (content != null) "replace" else "edit"
    }
}

/**
 * 解析并校验 memory_write 参数（纯函数，JVM 可测），非法时抛 [IllegalArgumentException]。
 * 互斥约定：edit 不接受 content（提示改用 replace），replace 不接受 edits，create 不接受 edits。
 */
internal fun parseMemoryWriteRequest(params: JsonObject): MemoryWriteCommand {
    val action = params["action"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("action is required and must be one of [create, edit, replace]")
    val id = params["id"]?.jsonPrimitive?.intOrNull
    val rawCategory = params["category"]?.jsonPrimitive?.contentOrNull
    // 空白 category 等于“不改”，不能算作一个改动点（否则空写会刷新 updated）
    val category = rawCategory?.takeIf { it.isNotBlank() }
    val title = params["title"]?.jsonPrimitive?.contentOrNull
    val content = params["content"]?.jsonPrimitive?.contentOrNull
    val confidenceFlags = params["confidence_flags"].stringArray("confidence_flags")
    val tags = params["tags"].stringArray("tags")
    val product = params["product"]?.jsonPrimitive?.contentOrNull
    val edits = parseMemoryEdits(params["edits"])

    if (category != null && category !in MEMORY_CATEGORIES) {
        throw IllegalArgumentException("invalid category: $category, must be one of $MEMORY_CATEGORIES")
    }
    if (confidenceFlags?.any { it.isBlank() } == true) {
        throw IllegalArgumentException("confidence_flags must not contain empty strings")
    }

    return when (action) {
        "create" -> {
            if (edits.isNotEmpty()) {
                throw IllegalArgumentException("edits are not accepted for action=create")
            }
            if (content.isNullOrBlank()) throw IllegalArgumentException("content is required for create")
            if (category == null) {
                throw IllegalArgumentException("category is required for create, must be one of $MEMORY_CATEGORIES")
            }
            if (title.isNullOrBlank()) throw IllegalArgumentException("title is required for create")
            val flags = confidenceFlags.orEmpty()
            if (flags.isEmpty()) {
                throw IllegalArgumentException("confidence_flags is required for create and must contain non-empty strings")
            }
            MemoryWriteCommand.Create(
                category = category,
                title = title,
                content = content,
                confidenceFlags = flags,
                tags = tags.orEmpty(),
                product = product,
            )
        }

        "edit" -> {
            if (content != null) {
                throw IllegalArgumentException(
                    "content is not accepted for action=edit; use action=replace for a full rewrite " +
                        "or provide edits for a local change"
                )
            }
            val recordId = id
                ?: throw IllegalArgumentException("id is required for edit")
            if (edits.isEmpty() && title == null && confidenceFlags == null && tags == null && product == null && category == null) {
                throw IllegalArgumentException(
                    "edit requires at least one change: provide edits, or one of category/title/confidence_flags/tags/product"
                )
            }
            MemoryWriteCommand.Update(
                id = recordId,
                content = null,
                edits = edits,
                category = category,
                title = title,
                confidenceFlags = confidenceFlags,
                tags = tags,
                product = product,
            )
        }

        "replace" -> {
            if (edits.isNotEmpty()) {
                throw IllegalArgumentException(
                    "edits are not accepted for action=replace; use action=edit for a local change"
                )
            }
            val recordId = id
                ?: throw IllegalArgumentException("id is required for replace")
            if (content.isNullOrBlank()) throw IllegalArgumentException("content is required for replace")
            MemoryWriteCommand.Update(
                id = recordId,
                content = content,
                edits = emptyList(),
                category = category,
                title = title,
                confidenceFlags = confidenceFlags,
                tags = tags,
                product = product,
            )
        }

        else -> throw IllegalArgumentException(
            "unknown action: $action, must be one of [create, edit, replace]"
        )
    }
}

private fun JsonElement?.stringArray(field: String): List<String>? {
    if (this == null || this is JsonNull) return null
    val array = this as? JsonArray
        ?: throw IllegalArgumentException("$field must be an array of strings")
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

private fun parseMemoryEdits(element: JsonElement?): List<MemoryEdit> {
    if (element == null || element is JsonNull) return emptyList()
    val array = element as? JsonArray
        ?: throw IllegalArgumentException("edits must be an array of {old_text, new_text} objects")
    if (array.size > MAX_EDITS_PER_CALL) {
        throw IllegalArgumentException("too many edits: ${array.size} (max $MAX_EDITS_PER_CALL per call)")
    }
    return array.mapIndexed { index, item ->
        val obj = item as? JsonObject
            ?: throw IllegalArgumentException("edits[$index] must be an object with old_text and new_text")
        val oldText = (obj["old_text"] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException("edits[$index].old_text is required")
        val newText = (obj["new_text"] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException(
                "edits[$index].new_text is required (pass an empty string to delete the matched text)"
            )
        if (oldText.isEmpty()) throw IllegalArgumentException("edits[$index].old_text must not be empty")
        MemoryEdit(oldText = oldText, newText = newText)
    }
}

/** 摘要只供模型回执；界面的完整差异元数据不经过此限制。 */
private fun AssistantMemory.toSummaryJsonObject(): JsonObject = boundedMemorySummary(this)

/**
 * 分层记忆工具集：memory_read / memory_write / memory_consolidate。
 *
 * 所有数据仅在明确调用后返回，不自动注入目录或 ACTIVE 正文。
 */
fun buildLayeredMemoryTools(json: Json, scopeId: String, repo: MemoryRepository): List<Tool> {
    val reports = LayeredMemoryReports()
    return listOf(
    Tool(
        name = "memory_read",
        description = """
            Search or read memory on demand. No directory or ACTIVE body is automatically injected.
            Query before relying on user preferences, existing agreements, historical parameters or ongoing tasks;
            ordinary small talk need not search. Use id for exact reading in the current scope (active by default).
            id cannot be combined with keywords/category/confidence/cursor. archived requires explicit status.
            Lists return has_more/next_cursor independently of each record's content_complete/next_offset.
            Continue lists with cursor and the same filters/status. Cursor binds scope and query, not a database snapshot.
            Read remaining body with id + version + returned offset (original Unicode code points, not lines).
            On version_changed restart at offset zero without version; never join different body versions.
            Metadata text fields are limited to 160 code points; tags/confidence_flags to 8 entries of 48 code points each.
            Limits are marked by metadata_truncated/truncated_fields. Total serialized output is bounded to $LAYERED_MEMORY_OUTPUT_LIMIT characters.
            Filters: keywords (matches title/tags/content, empty = browse by category), category, confidence substring, status.
            Category values: PARAMS (参数/配置), SOP (流程/步骤), METHOD (方法/技巧), CASES (案例/经验), ACTIVE (进行中事项), GENERAL (通用).
            Confidence flags vocabulary (see memory_write): ✅实测已验证, ⚠️单板待确认, 🔄待复核.
            Age rule: a record whose updated date is older than 7 days with ⚠️/待确认 confidence must be confirmed with the user first.
            中文：涉及记忆时先按需查询，禁止凭印象编造参数细节；列表翻页和正文续读互不替代。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject { put("type", "integer"); put("description", "Exact record id; excludes keywords/category/confidence/cursor") })
                    put("cursor", buildJsonObject { put("type", "string"); put("description", "List next_cursor; repeat unchanged filters and status") })
                    put("offset", buildJsonObject { put("type", "integer"); put("description", "Returned Unicode code-point offset; requires id and version") })
                    put("version", buildJsonObject { put("type", "string"); put("description", "Body hash returned by a prior read; requires id") })
                    put("keywords", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to match against title/tags/content; empty means browse by category")
                    })
                    put("category", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { MEMORY_CATEGORIES.forEach { add(it) } })
                        put("description", "Filter by category (PARAMS/SOP/METHOD/CASES/ACTIVE/GENERAL)")
                    })
                    put("confidence", buildJsonObject {
                        put("type", "string")
                        put("description", "Substring to match inside confidence_flags, e.g. ⚠️ or 待确认")
                    })
                    put("status", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("active")
                            add("archived")
                        })
                        put("description", "Record status, default: active")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max records to return (1-20), default: 5")
                    })
                },
                required = emptyList()
            )
        },
        execute = { input ->
            val payload = readLayeredMemory(scopeId, repo, input.jsonObject)
            listOf(UIMessagePart.Text(checkedMemoryOutput(payload)))
        }
    ),
    Tool(
        name = "memory_write",
        description = """
            Create or update a layered memory record.

            Updating is LOCAL by default: use action=edit with id and edits, where each edit is
            {"old_text": "...", "new_text": "..."}. Copy old_text exactly from the current record
            (read it with memory_read first); it must occur exactly once. A missing or ambiguous
            anchor is rejected and the record stays unchanged. Pass an empty new_text to delete the matched text.
            Same record, several spots: put them in one edits array (applied in order, all-or-nothing).
            Different records: call memory_write several times in the same turn. Those calls run one after
            another and cannot see each other's results, so never chain dependent changes across calls
            (create first, then edit its id in a later turn).
            Use action=replace (id + full content) only for restructuring a record, e.g. merging or condensing it.
            Before replace, completely read the SAME body version, including every continuation fragment.
            Partial reads may supply local anchors, but each anchor must still be unique in the latest full body.
            version is only a read consistency check, NOT a new write lock; existing write concurrency is unchanged.
            content is not accepted for action=edit.

            Optional field parameters are applied only when present; pass an empty string/array to clear
            title, tags, product or confidence_flags. create requires category, title, content and confidence_flags.
            Confidence flags recommended values: ✅实测已验证 (verified in practice), ⚠️单板待确认 (single-board, needs confirmation), 🔄待复核 (to recheck).
            Age rule: a record whose updated date is older than 7 days with ⚠️/待确认 confidence must be confirmed with the user first.
            Merge similar records: prefer edit over create to avoid duplicates.
            Do not store sensitive information (ethnicity, religion, political views, sex life, criminal records, etc.).
            Do not show memory content directly in the conversation unless the user explicitly asks.
            中文：默认用 action=edit + edits 做局部修改，old_text 必须从 memory_read 的正文里逐字复制且全文只出现一次；同一记录多点放在一次调用的 edits 里（按序、全成或全不成），不同记录才并行调用；只有合并/压缩整条记录才用 action=replace。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("create")
                            add("edit")
                            add("replace")
                        })
                        put("description", "create a new record, edit an existing one locally, or replace its whole content (required)")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Record id (required for edit and replace)")
                    })
                    put("edits", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("old_text", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Exact snippet copied from the record; must occur exactly once")
                                })
                                put("new_text", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Replacement text; empty string deletes the snippet")
                                })
                            })
                            put("required", buildJsonArray {
                                add("old_text")
                                add("new_text")
                            })
                        })
                        put("description", "Local changes for edit (max $MAX_EDITS_PER_CALL per call, applied in order, all-or-nothing)")
                    })
                    put("category", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { MEMORY_CATEGORIES.forEach { add(it) } })
                        put("description", "Category (required for create)")
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "Short title (required for create)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Full record body: required for create and replace, not accepted for edit")
                    })
                    put("confidence_flags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Confidence flags (required for create, non-empty strings)")
                    })
                    put("tags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Optional tags")
                    })
                    put("product", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional related product/model name")
                    })
                },
                required = listOf("action")
            )
        },
        execute = { input ->
            var diffMetadata: DiffMetadata? = null
            val payload = when (val command = parseMemoryWriteRequest(input.jsonObject)) {
                is MemoryWriteCommand.Create -> {
                    val result = repo.writeLayeredMemory(
                        assistantId = scopeId,
                        id = null,
                        category = command.category,
                        title = command.title,
                        content = command.content,
                        confidenceFlags = command.confidenceFlags,
                        tags = command.tags,
                        product = command.product,
                    )
                    buildJsonObject {
                        put("success", true)
                        put("mode", "create")
                        put("memory", result.memory.toSummaryJsonObject())
                    }
                }

                is MemoryWriteCommand.Update -> {
                    val result = repo.writeLayeredMemory(
                        assistantId = scopeId,
                        id = command.id,
                        category = command.category,
                        title = command.title,
                        content = command.content,
                        confidenceFlags = command.confidenceFlags,
                        tags = command.tags,
                        product = command.product,
                        edits = command.edits,
                    )
                    // 只把“改了哪几行”回给模型，不把整条记录再背一遍
                    val diff = result.previousContent
                        ?.let { generateUnifiedDiff(it, result.memory.content, "memory/#${result.memory.id}") }
                    diffMetadata = diff?.let { DiffMetadata(diff = it) }
                    buildJsonObject {
                        put("success", true)
                        put("mode", command.mode)
                        put("memory", result.memory.toSummaryJsonObject())
                        if (command.edits.isNotEmpty()) put("replacements", command.edits.size)
                        if (diff != null) {
                            val fragment = diff.codePointSlice(0, minOf(diff.codePointsLength(), RESULT_FRAGMENT_CHAR_LIMIT))
                            put("fragment", fragment)
                            put("fragment_truncated", fragment != diff)
                        }
                    }
                }
            }
            val boundedPayload = boundMemoryWriteReceipt(payload)
            listOf(UIMessagePart.Text(text = checkedMemoryOutput(boundedPayload), metadata = diffMetadata?.toMetadata()))
        }
    ),
    Tool(
        name = "memory_consolidate",
        description = """
            Layered memory maintenance.
            Actions:
            - rebuild_index: explicitly browse the current memory directory, without loading ACTIVE bodies; supports limit/cursor.
            - detect_conflicts: find records of the same category+product+overlapping tags that state the same key with different values (report only).
            - suggest_upgrades: when a ⚠️/待确认 record's key=value is confirmed by another record, suggest upgrading its confidence flag to ✅实测已验证 (report only). Apply a suggestion with memory_write (action=edit, id, confidence_flags only) — no need to rewrite the record content.
            - archive: archive records by id (ids required). dry_run=true only returns the ids that would be archived; dry_run=false actually archives them.
            After archiving, records disappear from the index and active set.
            All outputs are bounded. For report has_more, repeat the SAME action/ids/dry_run with next_cursor.
            Long reports use report_fragment: concatenate original code-point fragments then parse the complete JSON.
            report_version binds report content; changed read-only reports require restarting. Archive receipts are immutable.
            Report cursors expire on tool rebuild or session eviction;
            expired archive receipts are NOT automatically re-executed. success describes the operation, complete describes output.
            Directory cursors are bound to current scope/action. Report snapshots are bound to scope/action/parameters.
            中文：冲突检测与升级建议只报告不自动修改；归档默认 dry_run 预览，回执续页不会再次归档。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("rebuild_index")
                            add("detect_conflicts")
                            add("suggest_upgrades")
                            add("archive")
                        })
                        put("description", "Action to perform (required)")
                    })
                    put("cursor", buildJsonObject { put("type", "string"); put("description", "next_cursor; repeat unchanged action/ids/dry_run") })
                    put("limit", buildJsonObject { put("type", "integer"); put("description", "Directory page size (1-20, default 5)") })
                    put("ids", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "integer") })
                        put("description", "Record ids (required for archive, at most 10000 per operation)")
                    })
                    put("dry_run", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Only preview changes without applying them (default: true)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = { input ->
            val params = input.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull
                ?: error("action is required")
            val dryRun = params["dry_run"]?.jsonPrimitive?.contentOrNull
                ?.let { requireNotNull(it.toBooleanStrictOrNull()) { "dry_run must be a boolean" } } ?: true
            val cursor = params["cursor"]?.jsonPrimitive?.contentOrNull
            if (action == "rebuild_index") {
                require(params.keys.all { it in setOf("action", "cursor", "limit") }) { "directory only accepts action/cursor/limit" }
                val directoryParams = JsonObject(params.filterKeys { it in setOf("cursor", "limit") })
                val directory = readLayeredMemory(scopeId, repo, directoryParams, directory = true)
                listOf(UIMessagePart.Text(checkedMemoryOutput(directory)))
            } else {
                val payload = if (cursor != null && action == "archive") {
                    reports.resume(scopeId, params, cursor)
                } else {
                    val report = when (action) {

                        "detect_conflicts" -> {
                            val conflicts = detectConflicts(repo.getMemoryEntitiesByStatus(scopeId, "active"))
                            buildJsonObject {
                                put("action", "detect_conflicts")
                                put("count", conflicts.size)
                                put("conflicts", buildJsonArray {
                                    conflicts.forEach { conflict ->
                                        add(buildJsonObject {
                                            put("key", conflict.key)
                                            put("value_a", conflict.valueA)
                                            put("value_b", conflict.valueB)
                                            put("id_a", conflict.idA)
                                            put("id_b", conflict.idB)
                                        })
                                    }
                                })
                            }
                        }

                        "suggest_upgrades" -> {
                            val upgrades = suggestConfidenceUpgrades(repo.getMemoryEntitiesByStatus(scopeId, "active"))
                            buildJsonObject {
                                put("action", "suggest_upgrades")
                                put("count", upgrades.size)
                                put("upgrades", buildJsonArray {
                                    upgrades.forEach { upgrade ->
                                        add(buildJsonObject {
                                            put("id", upgrade.id)
                                            put("key", upgrade.key)
                                            put("value", upgrade.value)
                                            put("reason", upgrade.reason)
                                        })
                                    }
                                })
                            }
                        }

                        "archive" -> {
                            val ids = params["ids"]?.jsonArray?.map {
                                requireNotNull(it.jsonPrimitive.intOrNull) { "ids must contain integers" }
                            } ?: error("ids is required for archive")
                            require(ids.size <= 10_000) { "archive accepts at most 10000 ids per operation" }
                            if (dryRun) {
                                val wouldArchive = repo.getMemoryEntitiesByStatus(scopeId, "active")
                                    .filter { it.id in ids }
                                    .map { it.id }
                                buildJsonObject {
                                    put("action", "archive")
                                    put("dry_run", true)
                                    put("success", true)
                                    put("would_archive", buildJsonArray { wouldArchive.forEach { add(it) } })
                                }
                            } else {
                                val archived = repo.archiveMemories(scopeId, ids)
                                buildJsonObject {
                                    put("action", "archive")
                                    put("dry_run", false)
                                    put("success", true)
                                    put("archived", buildJsonArray { archived.forEach { add(it) } })
                                }
                            }
                        }

                        else -> error(
                            "unknown action: $action, must be one of [rebuild_index, detect_conflicts, suggest_upgrades, archive]"
                        )
                    }
                    if (cursor == null) reports.start(scopeId, params, report)
                    else reports.resume(scopeId, params, cursor, current = report)
                }
                listOf(UIMessagePart.Text(checkedMemoryOutput(payload)))
            }
        }
    ),
    )
}
