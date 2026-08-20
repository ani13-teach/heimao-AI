package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import java.time.LocalDate

internal val memoryListJson = Json { ignoreUnknownKeys = true }

/** 解析 JSON 字符串数组，失败时返回空列表。 */
internal fun parseStringArrayJson(raw: String): List<String> =
    runCatching { memoryListJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

@Serializable
data class ConflictReport(
    val key: String,
    val valueA: String,
    val valueB: String,
    val idA: Int,
    val idB: Int,
)

@Serializable
data class UpgradeSuggestion(
    val id: Int,
    val key: String,
    val value: String,
    val reason: String,
)

data class LayeredMemoryContext(
    val indexText: String,
    val activeMemories: List<AssistantMemory>,
    val indexCount: Int,
    val activeCount: Int,
)

/**
 * 转义 LIKE 模式中的特殊字符（% _ \），配合 SQL `ESCAPE '\'` 使用。
 */
internal fun escapeLike(input: String): String =
    input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * 构建分层记忆索引文本。每行格式：
 * `{id}|{category}|{title}|{updated}|{confidenceSummary}|{status}`
 * - title 为空显示 `Legacy #{id}`；updated 为 1970-01-01 显示 `unknown`
 * - confidenceSummary = flags join("/")，空则 "—"
 * 首行为说明行，不占用条目数；超出 maxEntries 或 maxChars 停止。
 */
internal fun buildLayeredIndex(entries: List<MemoryEntity>, maxEntries: Int, maxChars: Int): String {
    require(maxEntries > 0) { "maxEntries must be positive" }
    require(maxChars > 0) { "maxChars must be positive" }
    val header = "# Memory index (id|category|title|updated|confidence|status); use memory_read to load full records"
    val body = buildList {
        var remaining = maxChars - header.length - 1
        if (remaining > 0) {
            for (entry in entries) {
                if (size >= maxEntries || remaining <= 0) break
                val line = entry.toIndexLine()
                // 单条超预算时跳过（可能还有更短的条目），而不是终止整个索引
                if (line.length + 1 > remaining) continue
                add(line)
                remaining -= line.length + 1
            }
        }
    }
    val full = buildString {
        append(header)
        body.forEach { append('\n').append(it) }
    }
    // 硬性字符预算：即使表头本身超长也要截断
    return if (full.length <= maxChars) full else full.take(maxChars)
}

/** 索引字段规范化：去换行/回车，竖线替换为斜杠，超长截断，防止破坏行结构。 */
internal fun sanitizeIndexField(value: String, maxLen: Int): String {
    val cleaned = value.replace(Regex("[\\r\\n]+"), " ").replace('|', '/').trim()
    return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen)
}

internal fun MemoryEntity.toIndexLine(): String = buildString {
    append(id)
    append('|')
    append(sanitizeIndexField(category.ifBlank { "GENERAL" }, maxLen = 24))
    append('|')
    append(sanitizeIndexField(title.ifBlank { "Legacy #$id" }, maxLen = 60))
    append('|')
    append(if (updated == "1970-01-01") "unknown" else updated)
    append('|')
    append(sanitizeIndexField(parseStringArrayJson(confidenceFlags).joinToString("/").ifBlank { "—" }, maxLen = 40))
    append('|')
    append(if (status.isBlank()) "active" else status)
}

internal fun MemoryEntity.confidenceFlagsList(): List<String> = parseStringArrayJson(confidenceFlags)

internal fun MemoryEntity.tagsList(): List<String> = parseStringArrayJson(tags)

/**
 * 提取正文中的 key=value / key: value 对（key 不区分大小写，value 不含 , ; | 换行）。
 */
private val KEY_VALUE_REGEX = Regex("(?i)([a-z][a-z0-9_ -]{1,30})\\s*[:=]\\s*([^,;|\\n]{1,60})")

internal fun extractKeyValues(content: String): Map<String, String> =
    KEY_VALUE_REGEX.findAll(content).associate { match ->
        match.groupValues[1].trim().lowercase() to match.groupValues[2].trim()
    }

/**
 * 冲突检测：同 category + 同 product（双方 product 都为空时忽略该条件）+
 * tags 有交集 + 提取到相同 key 但不同 value。仅报告，不自动修改。
 */
internal fun detectConflicts(entries: List<MemoryEntity>): List<ConflictReport> {
    val reports = mutableListOf<ConflictReport>()
    for (i in entries.indices) {
        for (j in i + 1 until entries.size) {
            val a = entries[i]
            val b = entries[j]
            if (a.category != b.category) continue
            val productOk = (a.product.isEmpty() && b.product.isEmpty()) ||
                (a.product.isNotEmpty() && a.product == b.product)
            if (!productOk) continue
            if (a.tagsList().intersect(b.tagsList()).isEmpty()) continue
            val keysA = extractKeyValues(a.content)
            val keysB = extractKeyValues(b.content)
            for ((key, valueA) in keysA) {
                val valueB = keysB[key] ?: continue
                if (valueA != valueB) {
                    reports += ConflictReport(
                        key = key,
                        valueA = valueA,
                        valueB = valueB,
                        idA = a.id,
                        idB = b.id,
                    )
                }
            }
        }
    }
    return reports
}

private fun hasUnverifiedFlag(entity: MemoryEntity): Boolean {
    val flags = entity.confidenceFlagsList()
    return flags.any { it.contains("⚠️") || it.contains("待确认") }
}

private fun hasVerifiedFlag(entity: MemoryEntity): Boolean {
    val flags = entity.confidenceFlagsList()
    return flags.any { it.contains("✅") || it.contains("实测已验证") }
}

/**
 * 置信度升级建议：≥2 条记录同 product+category、tags 有交集、包含相同 key=value，
 * 且带 ⚠️（或"待确认"）的那条由另一条**已验证**（含 ✅ 且不含 ⚠️/待确认）的记录佐证
 * → 建议将该条升级为 ✅实测已验证。仅报告，不自动修改。
 */
internal fun suggestConfidenceUpgrades(entries: List<MemoryEntity>): List<UpgradeSuggestion> {
    val suggestions = LinkedHashMap<Triple<Int, String, String>, UpgradeSuggestion>()
    for (i in entries.indices) {
        for (j in i + 1 until entries.size) {
            val a = entries[i]
            val b = entries[j]
            if (a.category != b.category) continue
            val productOk = (a.product.isEmpty() && b.product.isEmpty()) ||
                (a.product.isNotEmpty() && a.product == b.product)
            if (!productOk) continue
            if (a.tagsList().intersect(b.tagsList()).isEmpty()) continue
            val keysA = extractKeyValues(a.content)
            val keysB = extractKeyValues(b.content)
            for ((key, valueA) in keysA) {
                val valueB = keysB[key] ?: continue
                if (valueA != valueB) continue
                if (hasUnverifiedFlag(a) && hasVerifiedFlag(b) && !hasUnverifiedFlag(b)) {
                    suggestions.putIfAbsent(
                        Triple(a.id, key, valueA),
                        UpgradeSuggestion(
                            id = a.id,
                            key = key,
                            value = valueA,
                            reason = "matching value confirmed by verified record #${b.id}; " +
                                "suggest upgrading confidence flag to ✅实测已验证",
                        )
                    )
                }
                if (hasUnverifiedFlag(b) && hasVerifiedFlag(a) && !hasUnverifiedFlag(a)) {
                    suggestions.putIfAbsent(
                        Triple(b.id, key, valueA),
                        UpgradeSuggestion(
                            id = b.id,
                            key = key,
                            value = valueA,
                            reason = "matching value confirmed by verified record #${a.id}; " +
                                "suggest upgrading confidence flag to ✅实测已验证",
                        )
                    )
                }
            }
        }
    }
    return suggestions.values.toList()
}

/**
 * 判断记录是否过期（超过 days 天未更新）。1970-01-01（未知）或解析失败不算过期。
 */
internal fun isStale(updated: String, now: LocalDate, days: Int): Boolean {
    if (updated == "1970-01-01") return false
    val date = runCatching { LocalDate.parse(updated) }.getOrNull() ?: return false
    return date.isBefore(now.minusDays(days.toLong()))
}

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        private const val PROMPT_MEMORY_LIMIT = 200
        private const val PROMPT_MEMORY_CHARACTER_LIMIT = 50_000

        private const val LAYERED_INDEX_ENTRY_LIMIT = 100
        private const val LAYERED_INDEX_CHAR_LIMIT = 12_000
        private const val LAYERED_ACTIVE_ENTRY_LIMIT = 8
        private const val LAYERED_ACTIVE_CHAR_LIMIT = 8_000
    }

    internal fun MemoryEntity.toAssistantMemory(): AssistantMemory = AssistantMemory(
        id = id,
        content = content,
        title = title,
        category = category,
        updated = updated,
        confidenceFlags = parseStringArrayJson(confidenceFlags),
        tags = parseStringArrayJson(tags),
        product = product,
        status = status,
    )

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { it.toAssistantMemory() }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { it.toAssistantMemory() }
    }

    suspend fun getMemoriesOfAssistantForPrompt(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistantForPrompt(assistantId, PROMPT_MEMORY_LIMIT)
            .map { it.toAssistantMemory() }
            .boundedForPrompt(PROMPT_MEMORY_CHARACTER_LIMIT)

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { it.toAssistantMemory() }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { it.toAssistantMemory() }
    }

    suspend fun getGlobalMemoriesForPrompt(): List<AssistantMemory> =
        getMemoriesOfAssistantForPrompt(GLOBAL_MEMORY_ID)

    /**
     * 分层记忆上下文：常驻注入的索引（≤100 条 / ≤12000 字符）+
     * ACTIVE 正文（≤8 条 / ≤8000 字符）。
     */
    suspend fun getLayeredMemoryContext(assistantId: String): LayeredMemoryContext {
        val indexEntries = memoryDAO.getMemoriesForLayeredIndex(assistantId, LAYERED_INDEX_ENTRY_LIMIT)
        val indexText = buildLayeredIndex(indexEntries, LAYERED_INDEX_ENTRY_LIMIT, LAYERED_INDEX_CHAR_LIMIT)
        val active = memoryDAO.getActiveMemoriesForPrompt(assistantId, LAYERED_ACTIVE_ENTRY_LIMIT)
            .map { it.toAssistantMemory() }
            .boundedForPrompt(LAYERED_ACTIVE_CHAR_LIMIT)
        val indexCount = (indexText.split('\n').size - 1).coerceAtLeast(0)
        return LayeredMemoryContext(
            indexText = indexText,
            activeMemories = active,
            indexCount = indexCount,
            activeCount = active.size,
        )
    }

    /**
     * 分层记忆检索。keyword/confidence 会做 LIKE 转义；limit 收敛到 1..50。
     */
    suspend fun searchMemories(
        assistantId: String,
        keyword: String?,
        category: String?,
        confidence: String?,
        status: String,
        limit: Int,
    ): List<AssistantMemory> {
        val safeLimit = limit.coerceIn(1, 50)
        // keyword 同样做 LIKE 转义，防止 %/_ 被当作通配符
        val safeKeyword = escapeLike(keyword?.trim().orEmpty())
        val confidencePattern = confidence?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "%${escapeLike(it)}%" }
        return memoryDAO.searchMemories(
            assistantId = assistantId,
            keyword = safeKeyword,
            category = category?.takeIf { it.isNotBlank() },
            confidencePattern = confidencePattern,
            status = status,
            limit = safeLimit,
        ).map { it.toAssistantMemory() }
    }

    /**
     * 按状态取全部记录（consolidate 用）。
     */
    suspend fun getMemoriesByStatus(assistantId: String, status: String): List<AssistantMemory> =
        memoryDAO.getMemoriesByStatus(assistantId, status).map { it.toAssistantMemory() }

    /** 按状态取实体记录（consolidate 纯函数用）。 */
    internal suspend fun getMemoryEntitiesByStatus(assistantId: String, status: String): List<MemoryEntity> =
        memoryDAO.getMemoriesByStatus(assistantId, status)

    /**
     * 写入/更新分层记忆。
     * - id == null：新建（category/title/content/confidence_flags 必填非空，updated=今天，status=active）
     * - id != null：合并旧记录——content 必填覆盖；category/title/confidenceFlags/tags/product
     *   为 null 表示不修改，非 null（可为空串/空数组）表示显式设置（允许清空）；updated 刷新为今天。
     *   旧记录不存在或不属于该助手时报错。
     */
    suspend fun writeLayeredMemory(
        assistantId: String,
        id: Int?,
        category: String?,
        title: String?,
        content: String,
        confidenceFlags: List<String>?,
        tags: List<String>?,
        product: String?,
    ): AssistantMemory {
        require(content.isNotBlank()) { "content is required" }
        if (id == null) {
            require(!category.isNullOrBlank()) { "category is required for create" }
            require(!title.isNullOrBlank()) { "title is required for create" }
            val flags = confidenceFlags.orEmpty()
            require(flags.isNotEmpty() && flags.all { it.isNotBlank() }) {
                "confidence_flags is required for create and must contain non-empty strings"
            }
            val entity = MemoryEntity(
                assistantId = assistantId,
                content = content.trim(),
                category = category!!.trim(),
                title = title!!.trim(),
                updated = LocalDate.now().toString(),
                confidenceFlags = memoryListJson.encodeToString(flags.map { it.trim() }),
                tags = memoryListJson.encodeToString(tags.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }),
                product = product.orEmpty().trim(),
                status = "active",
            )
            val newId = memoryDAO.insertMemory(entity).toInt()
            return entity.copy(id = newId).toAssistantMemory()
        }
        val old = memoryDAO.getMemoryById(id)
            ?: error("Memory record #$id not found")
        if (old.assistantId != assistantId) {
            error("Memory record #$id does not belong to this assistant")
        }
        val merged = old.copy(
            category = category?.takeIf { it.isNotBlank() }?.trim() ?: old.category,
            title = title?.trim() ?: old.title,
            content = content.trim(),
            confidenceFlags = confidenceFlags?.let {
                memoryListJson.encodeToString(it.map { f -> f.trim() })
            } ?: old.confidenceFlags,
            tags = tags?.let {
                memoryListJson.encodeToString(it.map { f -> f.trim() }.filter { f -> f.isNotEmpty() })
            } ?: old.tags,
            product = product?.trim() ?: old.product,
            updated = LocalDate.now().toString(),
        )
        memoryDAO.updateMemory(merged)
        return merged.toAssistantMemory()
    }

    /**
     * 归档指定 id（须属于该 assistantId），返回实际被置为 archived 的 id 列表。
     */
    suspend fun archiveMemories(assistantId: String, ids: List<Int>): List<Int> {
        val updated = mutableListOf<Int>()
        for (id in ids.distinct()) {
            val old = memoryDAO.getMemoryById(id) ?: continue
            if (old.assistantId != assistantId) continue
            if (old.status == "archived") continue
            memoryDAO.updateMemory(old.copy(status = "archived"))
            updated += id
        }
        return updated
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(
            content = content
        )
        memoryDAO.updateMemory(newMemory)
        return newMemory.toAssistantMemory()
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val memory = AssistantMemory(
            id = 0,
            content = content,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content
                )
            ).toInt()
        )
        return newMemory
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }
}

internal fun List<AssistantMemory>.boundedForPrompt(maxCharacters: Int): List<AssistantMemory> {
    require(maxCharacters > 0) { "maxCharacters must be positive" }
    var remaining = maxCharacters
    return buildList {
        for (memory in this@boundedForPrompt) {
            if (remaining <= 0) break
            val content = memory.content.take(remaining)
            if (content.isNotBlank()) {
                add(memory.copy(content = content))
                remaining -= content.length
            }
        }
    }
}
