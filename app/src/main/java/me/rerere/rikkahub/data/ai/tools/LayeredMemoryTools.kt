package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
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
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.detectConflicts
import me.rerere.rikkahub.data.repository.suggestConfidenceUpgrades

private val MEMORY_CATEGORIES = listOf("PARAMS", "SOP", "METHOD", "CASES", "ACTIVE", "GENERAL")

/**
 * memory_write 参数校验（纯函数，JVM 可测）。返回错误信息，合法时返回 null。
 */
internal fun validateWriteRequest(
    action: String?,
    id: Int?,
    category: String?,
    title: String?,
    content: String?,
    confidenceFlags: List<String>?,
): String? {
    if (action != "create" && action != "edit") {
        return "action is required and must be one of [create, edit]"
    }
    if (content.isNullOrBlank()) {
        return "content is required"
    }
    return when (action) {
        "create" -> {
            if (category.isNullOrBlank()) return "category is required for create, must be one of $MEMORY_CATEGORIES"
            if (category !in MEMORY_CATEGORIES) {
                return "invalid category: $category, must be one of $MEMORY_CATEGORIES"
            }
            if (title.isNullOrBlank()) return "title is required for create"
            val flags = confidenceFlags.orEmpty()
            if (flags.isEmpty() || flags.any { it.isBlank() }) {
                return "confidence_flags is required for create and must contain non-empty strings"
            }
            null
        }

        "edit" -> {
            if (id == null) return "id is required for edit"
            if (!category.isNullOrBlank() && category !in MEMORY_CATEGORIES) {
                return "invalid category: $category, must be one of $MEMORY_CATEGORIES"
            }
            val flags = confidenceFlags.orEmpty()
            if (flags.any { it.isBlank() }) {
                return "confidence_flags must not contain empty strings"
            }
            null
        }

        else -> null
    }
}

private fun AssistantMemory.toLayeredJsonObject(): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("id", id)
    put("category", category)
    put("title", title)
    put("updated", updated)
    put("confidence_flags", buildJsonArray { confidenceFlags.forEach { add(it) } })
    put("tags", buildJsonArray { tags.forEach { add(it) } })
    put("product", product)
    put("status", status)
    put("content", content)
}

/**
 * 分层记忆工具集：memory_read / memory_write / memory_consolidate。
 *
 * 分层记忆模式下 system prompt 只注入索引 + ACTIVE 正文，正文按需通过
 * memory_read 加载；memory_write 强制 schema（缺必填拒绝）；memory_consolidate
 * 提供冲突检测、置信度升级建议与归档。
 */
fun buildLayeredMemoryTools(json: Json, scopeId: String, repo: MemoryRepository): List<Tool> = listOf(
    Tool(
        name = "memory_read",
        description = """
            Search and load full memory records on demand (layered memory mode).
            The system prompt only injects a compact index and the most recent ACTIVE records;
            use this tool to fetch the full content of any other record before relying on it.
            Filters: keywords (matches title/tags/content, empty = browse by category), category, confidence substring, status.
            Category values: PARAMS (参数/配置), SOP (流程/步骤), METHOD (方法/技巧), CASES (案例/经验), ACTIVE (进行中事项), GENERAL (通用).
            Confidence flags vocabulary (see memory_write): ✅实测已验证, ⚠️单板待确认, 🔄待复核.
            Age rule: a record whose updated date is older than 7 days with ⚠️/待确认 confidence must be confirmed with the user first.
            中文：正文不会全部注入，回答前先用本工具检索相关记忆，禁止凭印象编造参数细节。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
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
            val params = input.jsonObject
            val keywords = params["keywords"]?.jsonPrimitive?.contentOrNull
            val category = params["category"]?.jsonPrimitive?.contentOrNull
            category?.takeIf { it.isNotBlank() }?.let {
                if (it !in MEMORY_CATEGORIES) error("invalid category: $it, must be one of $MEMORY_CATEGORIES")
            }
            val confidence = params["confidence"]?.jsonPrimitive?.contentOrNull
            val status = params["status"]?.jsonPrimitive?.contentOrNull ?: "active"
            if (status != "active" && status != "archived") error("invalid status: $status, must be active or archived")
            val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 5
            if (limit !in 1..20) error("limit must be between 1 and 20")

            val results = repo.searchMemories(
                assistantId = scopeId,
                keyword = keywords,
                category = category,
                confidence = confidence,
                status = status,
                limit = limit,
            )
            val payload = buildJsonObject {
                put("count", results.size)
                put("memories", buildJsonArray {
                    results.forEach { add(it.toLayeredJsonObject()) }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "memory_write",
        description = """
            Create or edit a layered memory record. The schema is enforced: create requires
            category, title, content and confidence_flags; edit requires id and content, and
            updates only the fields you provide (pass an empty string/array to clear title,
            tags, product or confidence_flags).
            Confidence flags recommended values: ✅实测已验证 (verified in practice), ⚠️单板待确认 (single-board, needs confirmation), 🔄待复核 (to recheck).
            Age rule: a record whose updated date is older than 7 days with ⚠️/待确认 confidence must be confirmed with the user first.
            Merge similar records: prefer edit over create to avoid duplicates.
            Do not store sensitive information (ethnicity, religion, political views, sex life, criminal records, etc.).
            Do not show memory content directly in the conversation unless the user explicitly asks.
            中文：相似记忆优先合并更新；含 ⚠️ 且超过 7 天未更新的记忆，先向用户确认再使用。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("create")
                            add("edit")
                        })
                        put("description", "create a new record or edit an existing one (required)")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Record id (required for edit)")
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
                        put("description", "Memory content (required)")
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
                required = listOf("action", "content")
            )
        },
        execute = { input ->
            val params = input.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull
            val id = params["id"]?.jsonPrimitive?.intOrNull
            val category = params["category"]?.jsonPrimitive?.contentOrNull
            val title = params["title"]?.jsonPrimitive?.contentOrNull
            val content = params["content"]?.jsonPrimitive?.contentOrNull
            val confidenceFlags = params["confidence_flags"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            val tags = params["tags"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            val product = params["product"]?.jsonPrimitive?.contentOrNull

            validateWriteRequest(
                action = action,
                id = id,
                category = category,
                title = title,
                content = content,
                confidenceFlags = confidenceFlags,
            )?.let { error("memory_write: $it") }

            val memory = when (action) {
                "create" -> repo.writeLayeredMemory(
                    assistantId = scopeId,
                    id = null,
                    category = category!!,
                    title = title!!,
                    content = content!!,
                    confidenceFlags = confidenceFlags!!.map { it.trim() }.filter { it.isNotEmpty() },
                    tags = tags?.map { it.trim() }.orEmpty().filter { it.isNotEmpty() },
                    product = product,
                )

                "edit" -> repo.writeLayeredMemory(
                    assistantId = scopeId,
                    id = id!!,
                    category = category?.takeIf { it.isNotBlank() },
                    title = title,
                    content = content!!,
                    confidenceFlags = confidenceFlags?.map { it.trim() },
                    tags = tags?.map { it.trim() },
                    product = product,
                )

                else -> error("unknown action: $action")
            }
            val payload = buildJsonObject {
                put("success", true)
                put("memory", memory.toLayeredJsonObject())
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "memory_consolidate",
        description = """
            Layered memory maintenance.
            Actions:
            - rebuild_index: rebuild the injected index summary (returns line/char counts and the index text).
            - detect_conflicts: find records of the same category+product+overlapping tags that state the same key with different values (report only).
            - suggest_upgrades: when a ⚠️/待确认 record's key=value is confirmed by another record, suggest upgrading its confidence flag to ✅实测已验证 (report only).
            - archive: archive records by id (ids required). dry_run=true only returns the ids that would be archived; dry_run=false actually archives them.
            After archiving, records disappear from the index and active set.
            中文：冲突检测与升级建议只报告不自动修改；归档默认 dry_run 预览。
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
                    put("ids", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "integer") })
                        put("description", "Record ids (required for archive)")
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
                ?.toBooleanStrictOrNull() ?: true
            val payload = when (action) {
                "rebuild_index" -> {
                    val ctx = repo.getLayeredMemoryContext(scopeId)
                    buildJsonObject {
                        put("action", "rebuild_index")
                        put("index_lines", ctx.indexCount)
                        put("index_chars", ctx.indexText.length)
                        put("active_count", ctx.activeCount)
                        put("index", ctx.indexText)
                    }
                }

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
                    val ids = params["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
                        ?: error("ids is required for archive")
                    if (dryRun) {
                        val wouldArchive = repo.getMemoryEntitiesByStatus(scopeId, "active")
                            .filter { it.id in ids }
                            .map { it.id }
                        buildJsonObject {
                            put("action", "archive")
                            put("dry_run", true)
                            put("would_archive", buildJsonArray { wouldArchive.forEach { add(it) } })
                        }
                    } else {
                        val archived = repo.archiveMemories(scopeId, ids)
                        buildJsonObject {
                            put("action", "archive")
                            put("dry_run", false)
                            put("archived", buildJsonArray { archived.forEach { add(it) } })
                        }
                    }
                }

                else -> error(
                    "unknown action: $action, must be one of [rebuild_index, detect_conflicts, suggest_upgrades, archive]"
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
)
